# Payment Gateway Clearing & Settlement Engine

The settlement engine that connects core software logic networks to regulated card networks, traditional banking systems, and utility billers. It processes external webhooks, runs fee margins, and tracks asynchronous payout corridors.

## Core System Requirements

### 1. Functional Requirements
* **Secure Webhook Verification:** Validates incoming gateway notification payloads against signing secrets via HMAC-SHA256 signatures to stop network spoofing.
* **Automated Fee Distribution Engine:** Computes processing fees dynamically using high-precision math parameters before broadcasting transaction updates.
* **Asynchronous Reconciliation Export:** Generates standardized JSON transaction dumps to allow tenants to complete out-of-band audit sweeps independently.
* Verify webhooks: When Stripe or another provider tells the system “payment succeeded,” it checks the HMAC signature to make sure the message genuinely came from the provider.
* Calculate fees: It determines how much the platform, partner, or processor should receive from each transaction.
* Reconcile transactions: It produces transaction files that can later be compared against bank/provider records for auditing.
* Retry failed notifications: If a client's webhook endpoint is temporarily unavailable, the system retries after increasing delays.
* Prevent replay attacks: A webhook older than 5 minutes is rejected so an intercepted message can't simply be submitted again.
* Rate-limit partners: Redis limits how many requests each API partner can make, protecting the system from traffic spikes.
Isolate card data: Card-processing infrastructure is separated from the rest of the network to help satisfy PCI-DSS security requirements.


### 2. Non-Functional Distributed Requirements
* **Eventual Consistency & Message Delivery:** Outbound webhook signals directed to a Client that run on an Eventual Consistency model. Delivery workers operate with an At-Least-Once promise using exponential backoff schedules (e.g., retry at 1m, 5m, 15m, 1h) until receiving a valid HTTP `200 OK` from the client app.
* **Replay Attack Thresholds & Clock Drift:** Webhook evaluation filters enforce a strict timestamp verification threshold. Payload headers with timestamp drift indicators older than 300 seconds are dropped immediately to protect against replay interception.
* **Distributed Rate Limiting:** Implements a token-bucket algorithm at the ingress gateway layer (utilizing Redis) to protect downstream connections from distributed denial of service (DDoS) spikes. Limit bounds are configured independently per partner API key token.
* **PCI-DSS Network Demilitarization:** Isolates card token processing environments inside a sandboxed network layer with zero direct internet or interior core mesh connectivity pathways, transmitting only opaque tokens to downstream partners.


## Flow
```
Your Client App
      │
      │ 1. Submit payment
      ▼
Payment Gateway
      │
      ├── Validate request
      ├── Check idempotency
      ├── Validate payment/message
      ├── Save transaction
      └── Publish event
      │
      ▼
 External Bank / Stripe / Biller
      │
      │ 2. Payment result
      ▼
Payment Gateway
      │
      │ 3. Webhook
      ▼
Your Client App

```

# How a Client Application Calls the Payment Gateway

If **you are the client application**, think of the payment gateway as a service you call whenever you need to **receive, track, or send money**.

## 1. Typical Flow

```text
Your Client Application
        │
        │ 1. Submit payment
        ▼
Payment Gateway
        │
        ├── Authenticate client
        ├── Check idempotency
        ├── Validate payment
        ├── Save transaction
        └── Publish event
        │
        ▼
External Payment Provider
(Stripe / Bank / Biller)
        │
        │ 2. Payment result
        ▼
Payment Gateway
        │
        │ 3. Signed webhook
        ▼
Your Client Application
        │
        ├── Verify webhook signature
        ├── Check timestamp
        ├── Check event/idempotency
        └── Process event
```

---

# 2. Your Application Initiates a Payment

Your backend might call:

```http
POST /api/v1/payments
Authorization: Bearer YOUR_API_KEY
Idempotency-Key: order-12345
Content-Type: application/json
```

With:

```json
{
  "amount": 10000,
  "currency": "USD",
  "customer_reference": "customer-789",
  "payment_method": "..."
}
```

The **`Idempotency-Key`** ensures that if your application accidentally sends the same request twice, the gateway doesn't process the payment twice.

---

# 3. The Gateway Processes the Payment

The gateway typically:

1. Authenticates your application.
2. Checks the idempotency key.
3. Validates the payment data.
4. Records the transaction in PostgreSQL.
5. Creates an outbox event.
6. Publishes the event to Kafka.
7. Sends the payment to the appropriate external provider/network.

The initial response could be:

```json
{
  "payment_id": "pay_12345",
  "status": "PENDING"
}
```

`PENDING` means the payment has been accepted for processing, but the final result isn't known yet.

---

# 4. External Provider Processes the Payment

For example:

```text
Payment Gateway
       │
       ▼
     Stripe
       │
       ▼
Card Network / Bank
       │
       ▼
Payment Result
```

Eventually, the external provider might report:

```text
payment.succeeded
```

The gateway then updates its internal payment record.

---

# 5. Gateway Sends a Webhook to Your Application

Your application exposes an endpoint such as:

```http
POST https://yourapp.com/webhooks/payment-gateway
```

The gateway sends a **signed webhook**:

```http
POST /webhooks/payment-gateway
Content-Type: application/json
X-Signature: t=178923011,v1=ab26987f...
```

Body:

```json
{
  "event": "payment.succeeded",
  "payment_id": "pay_12345",
  "amount": 10000,
  "currency": "USD"
}
```

The important point is:

> **Your application must NOT blindly trust the webhook simply because it arrived at your endpoint.**

It must verify that the gateway actually generated it.

---

# 6. Webhook Signature Verification Flow

The gateway and your application share a secret:

```text
Gateway
   │
   │ Shared webhook secret
   │
   ▼
Your Application
```

When generating the webhook, the gateway calculates an HMAC-SHA256 signature.

Conceptually:

```text
signature =
HMAC-SHA256(
    webhook_secret,
    timestamp + "." + raw_request_body
)
```

For example:

```text
timestamp = 178923011

body = {
  "event": "payment.succeeded",
  "payment_id": "pay_12345",
  ...
}

signed_payload =
178923011 + "." + body
```

The gateway generates:

```text
HMAC-SHA256(webhook_secret, signed_payload)
```

and puts the result in the header:

```http
X-Signature: t=178923011,v1=ab26987f...
```

---

# 7. Your Application Verifies the Webhook

Your application performs roughly these steps:

```text
Webhook received
       │
       ▼
Extract timestamp + signature
       │
       ▼
Check timestamp
       │
       ├── Older than 300 seconds? → REJECT
       │
       ▼
Read RAW request body
       │
       ▼
Construct:
timestamp + "." + raw_body
       │
       ▼
Calculate HMAC-SHA256
using shared secret
       │
       ▼
Compare calculated signature
with X-Signature
       │
       ├── Does NOT match → REJECT
       │
       ▼
Check event idempotency
       │
       ├── Already processed → return 200
       │
       ▼
Process payment event
       │
       ▼
Return HTTP 200
```

---

# 8. Why Check the Timestamp?

Suppose an attacker intercepts a legitimate webhook:

```text
payment.succeeded
payment_id = pay_12345
```

They could potentially try to send that exact message to your endpoint again.

This is called a **replay attack**.

The timestamp prevents an old signed message from being reused indefinitely.

For example:

```text
Current time:       12:00:00
Webhook timestamp:  11:59:30
Difference:             30 sec
                       ↓
                    ACCEPT
```

But:

```text
Current time:       12:00:00
Webhook timestamp:  11:40:00
Difference:           20 min
                       ↓
                    REJECT
```

Your stated design uses a **300-second (5-minute) tolerance**.

---

# 9. Why Use the Raw Request Body?

This is important for HMAC verification.

The signature is calculated against the exact payload that the gateway sent.

For example, these JSON objects contain the same logical information:

```json
{"amount":10000,"currency":"USD"}
```

and:

```json
{
  "currency": "USD",
  "amount": 10000
}
```

But their raw byte representation is different.

Therefore, your application should generally calculate the signature using the **raw HTTP request body before JSON parsing/reformatting**.

```text
HTTP Request
     │
     ├── Headers
     │
     └── RAW BODY
            │
            ▼
      Signature verification
            │
            ▼
        JSON parsing
            │
            ▼
       Business logic
```

---

# 10. What Happens After Successful Verification?

Once the signature and timestamp are valid:

```text
Verified Webhook
       │
       ▼
Check event ID / payment ID
       │
       ├── Already processed → HTTP 200
       │
       ▼
Update payment
       │
       ▼
Trigger business logic
       │
       ▼
HTTP 200 OK
```

For example:

```json
{
  "payment_id": "pay_12345",
  "status": "SUCCEEDED"
}
```

Your application can then:

* Mark the customer's order as paid.
* Release the product/service.
* Update its own ledger.
* Notify the customer.
* Trigger fulfilment.

---

# 11. What If Your Application Is Down?

The gateway should use **at-least-once delivery**.

For example:

```text
Gateway
   │
   ├── Attempt 1 → Your App ❌
   │
   ├── Wait 1 minute
   │
   ├── Attempt 2 → Your App ❌
   │
   ├── Wait 5 minutes
   │
   ├── Attempt 3 → Your App ❌
   │
   ├── Wait 15 minutes
   │
   └── Attempt 4 → Your App → HTTP 200 ✅
```

This means your webhook handler must itself be **idempotent**.

The same webhook may legitimately arrive more than once.

---

# 12. Complete End-to-End Picture

```text
┌─────────────────────────┐
│   YOUR APPLICATION      │
└────────────┬────────────┘
             │
             │ POST /api/v1/payments
             │ Idempotency-Key: order-123
             ▼
┌─────────────────────────┐
│    PAYMENT GATEWAY      │
│                         │
│ Authentication          │
│ Idempotency             │
│ Validation              │
│ PostgreSQL              │
│ Transactional Outbox    │
│ Kafka                   │
└────────────┬────────────┘
             │
             │ Payment request
             ▼
┌─────────────────────────┐
│ Stripe / Bank / Biller  │
└────────────┬────────────┘
             │
             │ Payment result
             ▼
┌─────────────────────────┐
│    PAYMENT GATEWAY      │
│                         │
│ Update payment          │
│ Generate signed webhook │
└────────────┬────────────┘
             │
             │ POST /webhooks/payment-gateway
             │ X-Signature: t=...,v1=...
             ▼
┌─────────────────────────┐
│   YOUR APPLICATION      │
│                         │
│ 1. Verify timestamp     │
│ 2. Verify HMAC          │
│ 3. Check idempotency    │
│ 4. Process event        │
│ 5. Return HTTP 200      │
└─────────────────────────┘
```

## Key takeaway

There are **two separate security/idempotency concerns**:

| Mechanism                  | Purpose                                                                                |
| -------------------------- | -------------------------------------------------------------------------------------- |
| **API authentication**     | Proves that *your application* is allowed to call the gateway                          |
| **Request idempotency**    | Prevents your payment request being processed twice                                    |
| **Webhook HMAC signature** | Proves that an incoming webhook was generated by the gateway                           |
| **Webhook timestamp**      | Prevents old signed webhooks from being replayed                                       |
| **Webhook idempotency**    | Prevents your application from processing the same event twice                         |
| **At-least-once delivery** | Ensures the gateway retries if your application doesn't successfully receive the event |

So the fundamental pattern is:

> **Your application authenticates when calling the gateway; the gateway authenticates itself when calling your application back.**


## Essential Core API Endpoints

### 1. Inbound Inflow Capture Hook
* **Endpoint:** `POST /api/v1/settlements/webhooks/stripe`
* **Headers:** `X-Signature: t=178923011,v1=ab26987f...`
* **Payload:**
```json
{
  "event": "payment_intent.succeeded",
  "data": {
    "object": {
      "id": "pi_stripe_capture_99128",
      "amount": 10000,
      "currency": "usd"
    }
  }
}
```

### 2. Dispatch External Bank Payout Wire
* **Endpoint:** `POST /api/v1/settlements/disbursements`
* **Headers:** `Authorization: Bearer <authorized_partner_token>`
* **Payload:**
```json
{
  "payout_reference": "po_clear_00921",
  "amount": 5000,
  "currency": "USD",
  "bank_identifier": "058",
  "account_number": "0123456789"
}
```

# Useful commands
./mvnw clean compile
./mvnw test
docker compose up -d
docker compose down -v

./mvnw -Dtest=PaymentServiceUnitTest test
./mvnw -Dtest=WebhookSignatureVerifierTest,PaymentServiceUnitTest test
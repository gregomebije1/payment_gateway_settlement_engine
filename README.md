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


# 

=========
Architectural Analysis & Data MappingThis Clearing & Settlement Engine acts as the crucial boundary layer connecting your interior services to regulated card networks, banks, and downstream merchants. Because it manages money flows across corporate lines, its schema must satisfy strict PCI-DSS tokenization tracking, high-precision fee distribution matrices, and robust webhook retry state machine constraints.To achieve this, the schema is structured into modular component blocks:The Payment/Disbursement Core: Implements opaque tokenization to isolate primary card data (PAN) and satisfy PCI-DSS network demilitarization bounds.The Automated Fee Distribution Engine: Tracks multi-tenant tier cuts using fixed-point high-precision math (basis points).The Outbound Webhook Retry State Machine: Handles the At-Least-Once delivery promise, capturing exponential backoff attempt histories and isolating stuck events into structural Dead Letter Queues (DLQ).Complete Production Payment Gateway & Settlement DDL Scriptsql-- ============================================================================
-- 1. EXTENSIONS & TRANSACTION ENUMS
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TYPE payment_status   AS ENUM ('PENDING', 'AUTHORIZED', 'CAPTURED', 'SETTLED', 'FAILED', 'REFUNDED');
CREATE TYPE payout_status    AS ENUM ('INITIATED', 'PROCESSING', 'SUCCESS', 'FAILED');
CREATE TYPE webhook_state    AS ENUM ('QUEUED', 'SENDING', 'DELIVERED', 'RETRYING', 'DEAD_LETTER');
CREATE TYPE fee_split_target AS ENUM ('PROCESSOR_CUT', 'PLATFORM_MARGIN', 'PARTNER_REVENUE');

-- ============================================================================
-- 2. PCI-DSS TOKEN ENCLAVE & TRANSACTION MANAGEMENT
-- ============================================================================

-- Merchant Tenants / Partner Profiles
CREATE TABLE merchant_partners (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    partner_reference VARCHAR(64) UNIQUE NOT NULL, -- e.g., 'partner_abc'
    api_key_hash BYTEA NOT NULL, -- SHA-256 hash of the API Token for rate limiting lookups
    webhook_secret VARCHAR(255) NOT NULL, -- Shared secret key used for HMAC-SHA256 signatures
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Core Settlement Transactions
CREATE TABLE gateway_payments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    merchant_id UUID NOT NULL REFERENCES merchant_partners(id) ON DELETE RESTRICT,
    payment_reference VARCHAR(64) NOT NULL, -- e.g., 'pay_12345'
    
    -- PCI-DSS NET DEMILITARIZATION BOUNDS: Direct card details are strictly forbidden.
    -- Only opaque, non-reversible token strings are stored here.
    pci_opaque_token VARCHAR(255) NOT NULL, 
    
    -- MONETARY VALUE MINOR UNITS: High precision handling using cents/pence
    gross_amount BIGINT NOT NULL CHECK (gross_amount > 0),
    net_amount BIGINT NOT NULL CHECK (net_amount >= 0),
    fee_total BIGINT NOT NULL DEFAULT 0 CHECK (fee_total >= 0),
    currency VARCHAR(3) NOT NULL, -- ISO 4217 code (e.g., 'USD')
    status payment_status NOT NULL DEFAULT 'PENDING',
    
    -- IDEMPOTENCY KEY: Enforces atomic processing boundary across client calls
    idempotency_key VARCHAR(255) NOT NULL,
    
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Enforce scoped unique index to isolate idempotency keys per partner
    CONSTRAINT uk_partner_idempotency UNIQUE (merchant_id, idempotency_key)
);

-- Speeds up accounting ledger sweeps and audit searches
CREATE INDEX idx_gateway_payments_status_date ON gateway_payments (status, created_at DESC);

-- ============================================================================
-- 3. HIGH-PRECISION AUTOMATED FEE DISTRIBUTION ENGINE
-- ============================================================================

-- Configuration rules specifying split calculations (in basis points: 1 bps = 0.01%)
CREATE TABLE partner_fee_rules (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    merchant_id UUID NOT NULL REFERENCES merchant_partners(id) ON DELETE RESTRICT,
    currency VARCHAR(3) NOT NULL,
    base_fixed_fee BIGINT NOT NULL DEFAULT 0, -- Base fee per transaction in minor units
    variable_basis_points INT NOT NULL CHECK (variable_basis_points BETWEEN 0 AND 10000), -- 10000 = 100.00%
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Records exact distribution cuts for financial audits
CREATE TABLE transaction_fee_splits (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    payment_id UUID NOT NULL REFERENCES gateway_payments(id) ON DELETE RESTRICT,
    target_bucket fee_split_target NOT NULL,
    calculated_amount BIGINT NOT NULL CHECK (calculated_amount >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 4. OUTBOUND WEBHOOK DELIVERY STATE MACHINE (EVENTUAL CONSISTENCY)
-- ============================================================================

-- Queue managing outbound webhooks with exponential backoff attributes
CREATE TABLE outbound_webhook_queue (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    merchant_id UUID NOT NULL REFERENCES merchant_partners(id) ON DELETE RESTRICT,
    associated_payment_id UUID REFERENCES gateway_payments(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL, -- e.g., 'payment.succeeded'
    payload JSONB NOT NULL, -- The raw JSON payload
    
    -- Transmission tracking metrics
    status webhook_state NOT NULL DEFAULT 'QUEUED',
    attempt_count INT NOT NULL DEFAULT 0,
    max_retry_attempts INT NOT NULL DEFAULT 5,
    next_delivery_attempt TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index optimizing background worker polling
CREATE INDEX idx_webhook_worker_polling 
ON outbound_webhook_queue (next_delivery_attempt ASC, status) 
WHERE status IN ('QUEUED', 'RETRYING');

-- History tracking for failed transmission attempts
CREATE TABLE webhook_attempt_history (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    webhook_queue_id UUID NOT NULL REFERENCES outbound_webhook_queue(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    http_status_returned INT, -- e.g., 503, 404, or NULL if timed out
    response_body TEXT,
    executed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- 5. PROCEDURAL TRIGGERS & REPLAY ATTACK DEFENCES
-- ============================================================================

/**
 * AUTOMATED TRIGGER RULE: Fee Split Distribution Calculator
 * Intercepts newly captured payments, runs fixed-point fee calculations, 
 * distributes cuts across targets, and modifies net settlement totals automatically.
 */
CREATE OR REPLACE FUNCTION process_automated_fee_distribution()
RETURNS TRIGGER AS $$
DECLARE
    v_fee_rule RECORD;
    v_variable_fee BIGINT;
    v_total_fee BIGINT;
    v_processor_cut BIGINT;
    v_platform_margin BIGINT;
BEGIN
    -- Only run calculation when a payment updates into a valid CAPTURED state
    IF NEW.status = 'CAPTURED' AND (OLD.status IS NULL OR OLD.status <> 'CAPTURED') THEN
        
        -- Pull fee split structure for the tenant
        SELECT base_fixed_fee, variable_basis_points 
        INTO v_fee_rule
        FROM partner_fee_rules
        WHERE merchant_id = NEW.merchant_id AND currency = NEW.currency;
        
        IF FOUND THEN
            -- High precision calculation using integer basis points
            v_variable_fee := (NEW.gross_amount * v_fee_rule.variable_basis_points) / 10000;
            v_total_fee    := v_fee_rule.base_fixed_fee + v_variable_fee;
            
            -- Allocate individual fee slices (e.g., standard 40/60 distribution split)
            v_processor_cut    := (v_total_fee * 4000) / 10000;
            v_platform_margin  := v_total_fee - v_processor_cut;
            
            -- Write trace auditing records
            INSERT INTO transaction_fee_splits (payment_id, target_bucket, calculated_amount) VALUES
            (NEW.id, 'PROCESSOR_CUT', v_processor_cut),
            (NEW.id, 'PLATFORM_MARGIN', v_platform_margin);
            
            -- Modify final entry column totals
            NEW.fee_total  := v_total_fee;
            NEW.net_amount := NEW.gross_amount - v_total_fee;
        ELSE
            -- Default path if no specific fee mapping is configured
            NEW.net_amount := NEW.gross_amount;
        END IF;
        
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_execute_fee_splits
    BEFORE INSERT OR UPDATE ON gateway_payments
    FOR EACH ROW
    EXECUTE FUNCTION process_automated_fee_distribution();
Use code with caution.🔄 End-to-End API Transaction FlowThis sequence demonstrates how a tenant client application triggers operations across your infrastructure, culminating in an asynchronous webhook lifecycle.Step 1: Client Application Initiates a Payment RequestWhen a client backend calls your gateway proxy, it submits a payload containing a unique token and a unique Idempotency-Key header to block double-billing.sql-- Core Setup Context: Establish a valid active partner tenant configuration
INSERT INTO merchant_partners (id, partner_reference, api_key_hash, webhook_secret)
VALUES (
    '8c9034ff-9486-4fb4-9c98-dfd8076596b4', 
    'partner_tenant_retail', 
    '\x7f83b1657ff1153', -- Pre-hashed API Token
    'whsec_shared_secret_key_string'
);

-- Configure a standard fee schedule rule: 30 cents fixed fee + 250 basis points (2.5%)
INSERT INTO partner_fee_rules (merchant_id, currency, base_fixed_fee, variable_basis_points)
VALUES ('8c9034ff-9486-4fb4-9c98-dfd8076596b4', 'USD', 30, 250);

-- THE API INGRESS TRANSACTION: Insert a new payment request
-- Gross amount is $100.00 (10000 minor units) with status initially set to PENDING
INSERT INTO gateway_payments (id, merchant_id, payment_reference, pci_opaque_token, gross_amount, net_amount, currency, status, idempotency_key)
VALUES (
    'a75d506d-b892-4fdb-963d-b4b1a4576361',
    '8c9034ff-9486-4fb4-9c98-dfd8076596b4',
    'pay_order_12345',
    'tok_pci_sandbox_card_vault_mask_883a',
    10000,
    10000, -- Matches gross initially
    'USD',
    'PENDING',
    'idempotency_header_token_order_12345'
);
Use code with caution.At this point, the gateway responds to the client backend with 200 OK and a payload indicating {"status": "PENDING"}.Step 2: External Payment Rail Capture & Fee Distribution Function TriggerEventually, the external acquiring bank or network processor executes the payment and triggers an internal state transition to CAPTURED.sql-- Update statement changes status to CAPTURED, firing the BEFORE UPDATE database trigger
UPDATE gateway_payments 
SET status = 'CAPTURED' 
WHERE id = 'a75d506d-b892-4fdb-963d-b4b1a4576361';

-- Let's inspect the results to verify that the high-precision fee engine calculated everything correctly:
SELECT gross_amount, fee_total, net_amount, status 
FROM gateway_payments 
WHERE id = 'a75d506d-b892-4fdb-963d-b4b1a4576361';

-- RESULT CONFIRMATION:
-- gross_amount | fee_total | net_amount |  status  
-- --------------+-----------+------------+----------
--  10000        | 280       | 9720       | CAPTURED
-- (Calculation Proof: Fixed 30 + (10000 * 0.025) = 280 cents fee. Net is 10000 - 280 = 9720)
Use code with caution.Step 3: Gateway Dispatches an Outbound Webhook via Retry State MachineOnce the processing layer confirms the capture, an outbox background worker queues an outgoing tracking event payload to notify the client application.sql-- Append a new tracking block event into the processing worker table
INSERT INTO outbound_webhook_queue (id, merchant_id, associated_payment_id, event_type, payload)
VALUES (
    'e92a2a0a-fa72-4648-8df0-f9ab7c427329',
    '8c9034ff-9486-4fb4-9c98-dfd8076596b4',
    'a75d506d-b892-4fdb-963d-b4b1a4576361',
    'payment.succeeded',
    '{"event": "payment.succeeded", "payment_id": "pay_order_12345", "amount": 10000, "currency": "USD"}'::jsonb
);
Use code with caution.The Background Worker Retrying Loop LogicYour background daemon worker executes a loop every few seconds to find and process events that are ready for delivery:sql-- Find pending hooks where the current time is greater than or equal to next_delivery_attempt
SELECT id, payload FROM outbound_webhook_queue
WHERE status IN ('QUEUED', 'RETRYING') AND next_delivery_attempt <= CURRENT_TIMESTAMP
FOR UPDATE SKIP LOCKED; -- High performance non-blocking worker thread locking mechanism
Use code with caution.If the client application's endpoint is offline and returns an error (e.g., HTTP 503 Service Unavailable), the worker captures the error details and calculates an exponential backoff delay to reschedule the next delivery attempt:sql-- Append the execution trace log history record
INSERT INTO webhook_attempt_history (webhook_queue_id, attempt_number, http_status_returned, response_body)
VALUES ('e92a2a0a-fa72-4648-8df0-f9ab7c427329', 1, 503, 'Gateway Timeout / Connection Refused');

-- Update the state machine block: increment attempt counter and scale next delivery attempt window
UPDATE outbound_webhook_queue
SET 
    attempt_count = 1,
    status = 'RETRYING',
    -- Exponentially scale delay: push attempt window back by (2 ^ attempt_count) minutes
    next_delivery_attempt = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'e92a2a0a-fa72-4648-8df0-f9ab7c427329';
Use code with caution.If the worker reaches the max_retry_attempts limit without receiving an HTTP 200 OK acknowledgment, it flags the record as DEAD_LETTER. This isolates the entry from the active processing lane so engineers can investigate manually without stalling the rest of the queue.sql-- Dead Letter Queue (DLQ) containment action rule
UPDATE outbound_webhook_queue
SET 
    status = 'DEAD_LETTER',
    updated_at = CURRENT_TIMESTAMP
WHERE id = 'e92a2a0a-fa72-4648-8df0-f9ab7c427329' AND attempt_count >= max_retry_attempts;
Use code with caution.Step 4: High-Performance Reconciliation Dump (Asynchronous Export Sweep)To allow partners to reconcile transactions independently without putting locking strain on active transaction tables, your system can run a non-blocking streaming read query:sql-- High-efficiency covering query for out-of-band tenant audit extractions
SELECT 
    p.payment_reference,
    p.gross_amount,
    p.fee_total,
    p.net_amount,
    p.currency,
    p.status,
    p.created_at AS settlement_date,
    json_object_agg(fs.target_bucket, fs.calculated_amount) AS fee_breakdown_splits
FROM gateway_payments p
LEFT JOIN transaction_fee_splits fs ON p.id = fs.payment_id
WHERE p.merchant_id = '8c9034ff-9486-4fb4-9c98-dfd8076596b4'
  AND p.created_at BETWEEN '2026-09-01 00:00:00+00' AND '2026-09-20 23:59:59+00'
GROUP BY p.id;
Use code with caution.

==========
Distributed Financial Systems Architecture: Accounting Ledger ManualThis manual defines the production-grade Chart of Accounts (COA) and Double-Entry Journal Entries for two tightly decoupled systems:The E-Wallet Orchestrator Engine: A customer-facing, assetless pass-through engine that leases balances, holds no physical cash custody, and delegates funding/payout actions to an external gateway.The Payment Gateway Clearing Engine: An infrastructure pipeline that manages incoming processor receivables, calculates basis-point transaction fee splits, and drives merchant settlements.Part 1: The E-Wallet Orchestrator EngineThe E-Wallet engine serves as a customer-facing transactional interface. Because it holds no direct custody of the underlying cash assets, its ledger focuses on managing receivable claims against the external gateway and tracking user balances as platform liabilities.1. Chart of Accounts (COA)Account NumberAccount NameTypeDescription1210Gateway Funding ReceivablesAssetFloating claims against the external gateway for incoming customer wallet deposits.1220Gateway Payout ClearingAssetTransient tracking lane for outbound customer bank withdrawals routed through the gateway.1230Settlement Bank AccountAssetCorporate bank account where the gateway deposits bulk payouts.2210User Wallet Balances (Liability Pool)LiabilityThe digital ledger balance leased to the user, reflecting their available spending power.2220Saga Transaction HoldsLiabilityTemporary escrow pool holding user funds during active multi-network workflows (e.g., utility purchases).4210Commission & Markup RevenueRevenueMargins earned by adding a convenience fee on top of the gateway’s base pricing.5210Gateway Processing ExpenseExpenseInterchange and processing costs deducted by the external provider.2. Core Transactional Flows & Journal EntriesFlow A: Multi-Channel Wallet Funding (Incoming Deposit)A user initiates a $100.00 deposit via credit card inside the wallet app. The wallet engine charges the user a $3.00 convenience fee margin on top of the transaction.Step 1: Upon Webhook Ingress Affirmation (Customer's card is successfully charged by the gateway)Debit: 1210 - Gateway Funding Receivables | +$103.00 (Asset increases: The gateway now owes you the deposit + your margin)Credit: 2210 - User Wallet Balances | +$100.00 (Liability increases: User's available digital balance is topped up)Credit: 4210 - Commission & Markup Revenue | +$3.00 (Revenue increases: Your platform fee margin is recognized)Step 2: Gateway Bulk Settlement (Occurs 24–48 hours later)The external gateway takes its own processing fee cut ($1.50) and deposits the remaining net balance ($101.50) into your corporate settlement bank account.Debit: 1230 - Settlement Bank Account | +$101.50 (Asset increases: Physical cash hits your actual corporate bank)Debit: 5210 - Gateway Processing Expense | +$1.50 (Expense increases: The gateway’s fee cut is recorded)Credit: 1210 - Gateway Funding Receivables | -$103.00 (Asset decreases: Clears out your pending claim against the gateway)Net platform profit tracked on this transaction block: $3.00 markup revenue - $1.50 gateway expense = $1.50 clear margin.Flow B: Peer-to-Peer (P2P) Internal TransferUser 1 transfers $25.00 to User 2 within the wallet ecosystem. Because this action is entirely internal and does not involve external banking movements, no asset accounts are altered.Debit: 2210 - User Wallet Balances (User 1) | -$25.00 (Liability decreases: Platform liability to User 1 decreases)Credit: 2210 - User Wallet Balances (User 2) | +$25.00 (Liability increases: Platform liability to User 2 increases)Flow C: Value-Added Service (VAS) Utility PurchaseA user purchases an airtime data subscription for $10.00.Step 1: Saga Orchestrator Initialization (Place a hold on funds to prevent double-spending race conditions)Debit: 2210 - User Wallet Balances | -$10.00 (Deducted from the user's active spending lane)Credit: 2220 - Saga Transaction Holds | +$10.00 (Moved into a temporary escrow liability lock)Step 2: Third-Party Utility API returns a success payloadThe hold is released and the external gateway is instructed to pay out the utility vendor from your corporate settlement account.Debit: 2220 - Saga Transaction Holds | -$10.00 (The internal transaction lock is cleared)Credit: 1220 - Gateway Payout Clearing | +$10.00 (Asset decreases: Your clearing account tracks that cash is leaving the system)
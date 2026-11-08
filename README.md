# Payment Gateway Clearing & Settlement Engine

-  How do you prevent the same idempotency key from creating two payment records? What happens if a webhook is delivered twice?


# Payment Gateway Clearing & Settlement Engine

A backend engineering project focused on the lifecycle of payment processing: **payment state management, fee calculation, signed webhooks, asynchronous notification delivery, and reconciliation**.

**Repository:** https://github.com/gregomebije1/payment_gateway_settlement_engine

## Problem

Payment processing crosses multiple system boundaries. A payment request may be accepted before a provider confirms the outcome, webhook notifications may be delivered more than once, and a client endpoint may be temporarily unavailable.

A reliable gateway therefore needs to distinguish request acceptance from payment completion, handle repeated messages safely, calculate transaction fees consistently, and provide enough information to investigate discrepancies.

## System flow

```text
Client application
       |
       | Payment request
       v
Payment gateway
       |
       | Validate request and idempotency
       | Record payment state
       v
External payment provider
       |
       | Provider result / signed webhook
       v
Payment gateway
       |
       | Validate and process event
       | Update payment state
       | Queue client notification
       v
Merchant application

Reconciliation compares internal records
with provider and settlement records.
```

## Core engineering areas

### 1. Payment lifecycle and state transitions

The design separates the initial payment request from the eventual provider outcome. A payment can begin in a pending state and transition when a verified provider event is processed.

Explicit lifecycle states help prevent a payment from being treated as completed merely because the gateway accepted the initial request.

State transitions should be validated so that duplicate or out-of-order events do not incorrectly move a payment backwards or trigger repeated financial effects.

### 2. Idempotency

Clients and external providers may retry requests when a response is lost or a network call times out.

The project describes idempotency keys associated with payment requests. The intended behaviour is to recognise a repeated request and avoid creating duplicate payment operations.

For a production implementation, the key must be persisted and checked atomically with the associated operation. Concurrent requests using the same key should not be able to create multiple financial effects.

### 3. Signed webhook verification

The design uses HMAC-SHA256 signatures to verify the authenticity and integrity of incoming webhook messages.

A robust verification path should:

- Verify the signature against the exact payload and the provider's signing scheme.
- Compare signatures safely.
- Validate the signed timestamp where the provider supports it.
- Reject invalid or stale messages.
- Track processed event identifiers to handle duplicate delivery.

A valid signature establishes message authenticity under the relevant secret-management assumptions; it does not, by itself, guarantee that an event has not already been processed.

### 4. Fee calculation

The fee engine models processing fees and the resulting net amount payable to a merchant.

For example, a hypothetical transaction of 10,000 minor units with a 280-unit fee produces:

| Field | Amount |
|---|---:|
| Gross amount | 10,000 |
| Processing fee | 280 |
| Net amount | 9,720 |

The fee calculation should use integer minor units and explicitly defined fee rules. The repository describes fixed and percentage-based fee components; the applicable formula, rounding policy, and limits should be verified against the implemented code.

### 5. Asynchronous webhook delivery

Client notification delivery is treated as a separate concern from payment processing. The design describes queued notifications, retry attempts with increasing delays, and a dead-letter state for events that exceed the retry limit.

This separation helps isolate temporary client failures from the main payment-processing path.

The delivery model should be treated as **at least once**, not exactly once: a client can receive the same notification more than once, so consumers should be idempotent.

### 6. Reconciliation and auditability

Reconciliation exports allow internal payment records to be compared with provider and bank settlement data.

Useful fields include:

- Payment reference and external provider reference.
- Gross, fee, and net amounts.
- Currency and payment status.
- Relevant timestamps.
- Settlement or payout status.

The objective is to make discrepancies discoverable and reviewable rather than assuming that an internal success state always matches the external financial outcome.

## API examples

The repository documents endpoints including:

- `POST /api/v1/settlements/webhooks/stripe` — receive provider webhook events.
- `POST /api/v1/settlements/disbursements` — initiate a payout or disbursement.

Example webhook header:

```http
X-Signature: t=<timestamp>,v1=<signature>
```

These paths and header conventions are examples from the project documentation. Confirm that they match the current controller mappings and provider integration before treating them as a runnable API specification.

## Reliability and testing priorities

The most important cases to test are:

- Invalid webhook signatures and stale timestamps.
- Duplicate payment requests with the same idempotency key.
- Duplicate provider events.
- Invalid payment state transitions.
- Fee calculations and rounding boundaries.
- Failed client webhook delivery and retry behaviour.
- Reconciliation mismatches between internal and external records.

Integration tests should verify database transactions and concurrent requests where the relevant implementation exists. A design description alone is not evidence that these failure cases are already covered by automated tests.

## Engineering focus

This project explores backend engineering concerns that matter in payment systems:

- Explicit payment lifecycle modelling.
- Safe handling of retries and duplicate events.
- Cryptographic webhook verification.
- Deterministic fee calculation.
- Asynchronous processing and failure isolation.
- Reconciliation and operational audit trails.

## Scope

This is a portfolio engineering project. The README describes the intended processing architecture and examples; implemented provider integrations, Redis-based rate limiting, retry workers, and production PCI-DSS controls should be claimed only where the code and tests substantiate them.

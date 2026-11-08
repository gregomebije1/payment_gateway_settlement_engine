
====
# Distributed Financial Systems Architecture: Accounting Ledger Manual

This manual defines the production-grade Chart of Accounts (COA) and Double-Entry Journal Entries for two tightly decoupled systems:

- **The E-Wallet Orchestrator Engine:** A customer-facing, assetless pass-through engine that leases balances, holds no physical cash custody, and delegates funding/payout actions to an external gateway.
- **The Payment Gateway Clearing Engine:** An infrastructure pipeline that manages incoming processor receivables, calculates basis-point transaction fee splits, and drives merchant settlements.

---

## Part 1: The E-Wallet Orchestrator Engine

The E-Wallet engine serves as a customer-facing transactional interface. Because it holds no direct custody of the underlying cash assets, its ledger focuses on managing receivable claims against the external gateway and tracking user balances as platform liabilities.

### 1. Chart of Accounts (COA)

| Account Number | Account Name | Type | Description |
|---|---|---|---|
| 1210 | Gateway Funding Receivables | Asset | Floating claims against the external gateway for incoming customer wallet deposits. |
| 1220 | Gateway Payout Clearing | Asset | Transient tracking lane for outbound customer bank withdrawals routed through the gateway. |
| 1230 | Settlement Bank Account | Asset | Corporate bank account where the gateway deposits bulk payouts. |
| 2210 | User Wallet Balances (Liability Pool) | Liability | The digital ledger balance leased to the user, reflecting their available spending power. |
| 2220 | Saga Transaction Holds | Liability | Temporary escrow pool holding user funds during active multi-network workflows (e.g., utility purchases). |
| 4210 | Commission & Markup Revenue | Revenue | Margins earned by adding a convenience fee on top of the gateway's base pricing. |
| 5210 | Gateway Processing Expense | Expense | Interchange and processing costs deducted by the external provider. |

### 2. Core Transactional Flows & Journal Entries

#### Flow A: Multi-Channel Wallet Funding (Incoming Deposit)

A user initiates a $100.00 deposit via credit card inside the wallet app. The wallet engine charges the user a $3.00 convenience fee margin on top of the transaction.

**Step 1: Upon Webhook Ingress Affirmation** *(Customer's card is successfully charged by the gateway)*

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 1210 – Gateway Funding Receivables | +$103.00 | Asset increases: The gateway now owes you the deposit + your margin |
| Credit | 2210 – User Wallet Balances | +$100.00 | Liability increases: User's available digital balance is topped up |
| Credit | 4210 – Commission & Markup Revenue | +$3.00 | Revenue increases: Your platform fee margin is recognized |

**Step 2: Gateway Bulk Settlement** *(Occurs 24–48 hours later)*

The external gateway takes its own processing fee cut ($1.50) and deposits the remaining net balance ($101.50) into your corporate settlement bank account.

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 1230 – Settlement Bank Account | +$101.50 | Asset increases: Physical cash hits your actual corporate bank |
| Debit | 5210 – Gateway Processing Expense | +$1.50 | Expense increases: The gateway's fee cut is recorded |
| Credit | 1210 – Gateway Funding Receivables | -$103.00 | Asset decreases: Clears out your pending claim against the gateway |

> **Net platform profit tracked on this transaction block:** $3.00 markup revenue − $1.50 gateway expense = **$1.50 clear margin**.

---

#### Flow B: Peer-to-Peer (P2P) Internal Transfer

User 1 transfers $25.00 to User 2 within the wallet ecosystem. Because this action is entirely internal and does not involve external banking movements, no asset accounts are altered.

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 2210 – User Wallet Balances (User 1) | -$25.00 | Liability decreases: Platform liability to User 1 decreases |
| Credit | 2210 – User Wallet Balances (User 2) | +$25.00 | Liability increases: Platform liability to User 2 increases |

---

#### Flow C: Value-Added Service (VAS) Utility Purchase

A user purchases an airtime data subscription for $10.00.

**Step 1: Saga Orchestrator Initialization** *(Place a hold on funds to prevent double-spending race conditions)*

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 2210 – User Wallet Balances | -$10.00 | Deducted from the user's active spending lane |
| Credit | 2220 – Saga Transaction Holds | +$10.00 | Moved into a temporary escrow liability lock |

**Step 2: Third-Party Utility API returns a success payload**

The hold is released and the external gateway is instructed to pay out the utility vendor from your corporate settlement account.

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 2220 – Saga Transaction Holds | -$10.00 | The internal transaction lock is cleared |
| Credit | 1220 – Gateway Payout Clearing | +$10.00 | Asset decreases: Your clearing account tracks that cash is leaving the system |

---

## Part 2: The Payment Gateway Clearing Engine

The Payment Gateway operates as a clearing broker for merchant tenants. It interfaces directly with card networks and acquiring banks, processing gross receivables, extracting platform processing fees, and managing payout corridors.

### 1. Chart of Accounts (COA)

| Account Number | Account Name | Type | Description |
|---|---|---|---|
| 1110 | Processor Receivables (In-Transit) | Asset | Funds captured by card networks but not yet deposited into the gateway's bank. |
| 1120 | Settlement Bank Account | Asset | Corporate bank account used to receive network settlements and dispatch payouts. |
| 2110 | Merchant Settlement Payables | Liability | Net funds owed to your merchant tenants that are available for withdrawal. |
| 4110 | Gateway Processing Fee Revenue | Revenue | Gross margins earned by the platform from your variable/fixed transaction fee splits. |
| 5110 | Interchange/Processor Expense | Expense | Upstream interchange fees charged by core Visa/Mastercard card networks or card factories. |

### 2. Core Transactional Flows & Journal Entries

#### Flow A: A Customer Pays a Merchant $100.00

The customer buys a product from a merchant using your gateway proxy. Your automated fee engine calculates a $2.80 platform processing fee split.

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 1110 – Processor Receivables (In-Transit) | +$100.00 | Asset increases: Core network card processor owes you the gross transaction amount |
| Credit | 2110 – Merchant Settlement Payables | +$97.20 | Liability increases: Net balance owed to your merchant tenant |
| Credit | 4110 – Gateway Processing Fee Revenue | +$2.80 | Revenue increases: Your platform's gross processing fee margin |

---

#### Flow B: Card Network Settles Cash into the Gateway Account

The card network settles the transaction, deducting an upstream interchange cost of $1.10 before moving the cash into your account.

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 1120 – Settlement Bank Account | +$98.90 | Asset increases: Cash physically received from the processor |
| Debit | 5110 – Interchange/Processor Expense | +$1.10 | Expense increases: Cost deducted by the upstream processing card bank |
| Credit | 1110 – Processor Receivables (In-Transit) | -$100.00 | Asset decreases: In-transit clearing balance resets to zero |

> **Net platform profit for this specific lifecycle:** $2.80 Gross Revenue − $1.10 Interchange Expense = **$1.70 net margin**.

---

#### Flow C: Asynchronous Payout Dispatched to Merchant Account

The merchant triggers a withdrawal request to transfer their settled earnings ($97.20) out of the gateway into their local corporate business bank account.

| Entry | Account | Amount | Effect |
|---|---|---|---|
| Debit | 2110 – Merchant Settlement Payables | -$97.20 | Liability decreases: The platform has cleared its debt obligation to the merchant |
| Credit | 1120 – Settlement Bank Account | -$97.20 | Asset decreases: Cash leaves your settlement bank account |

---

## Part 3: Automated Three-Way Reconciliation

Because the E-Wallet engine relies on external systems, it uses an asynchronous background worker loop to perform Three-Way Reconciliation. It matches internal saga execution logs against incoming gateway transaction files and actual bank data feeds.

```
┌───────────────────────────────┐        ┌───────────────────────────────┐
│     Saga Orchestration Logs   │        │   Gateway Settlement Export   │
│   (Internal Intent Register)  │        │   (External API / CSV Feed)   │
└───────────────┬───────────────┘        └───────────────┬───────────────┘
                │                                         │
                └───────────────────┬─────────────────────┘
                                     ▼
                     ┌──────────────────────────────┐
                     │  Automated Reconciler Engine  │
                     └──────────────┬───────────────┘
                                     │
                                     ▼
                     ┌──────────────────────────────┐
                     │    Bank Statement Feed        │
                     │     (MT940 / BAI2 Format)     │
                     └──────────────────────────────┘
```

The background thread executes this reconciliation process by running a high-performance filtering query to identify discrepancy anomalies where internal ledger intents do not match external reality records:

```sql
SELECT 
    sol.id AS saga_id,
    sol.trace_id,
    sol.amount AS internal_expected_amount,
    wfi.amount AS gateway_intended_amount,
    wfi.provider_intent_id AS external_reference,
    (sol.amount - wfi.amount) AS balance_discrepancy_delta
FROM saga_orchestration_logs sol
JOIN wallets w ON sol.wallet_id = w.id
JOIN wallet_funding_intents wfi ON wfi.wallet_id = w.id
WHERE sol.status = 'COMPLETED'
  AND wfi.status = 'SUCCESS'
  -- Flag an exception anomaly if the internal ledger does not match gateway intent data
  AND sol.amount <> wfi.amount;
```

If the query returns data rows, the automated workflow flags those transaction records as `DISCREPANCY_LOCKED` and sends them to a manual audit review stream, ensuring zero data drift passes into your system.
===================
Scalling a springboot application on AWS
========
To scale a Spring Boot application on AWS, you must transition to a stateless architecture and deploy using horizontal scaling mechanisms.
1. Compute & Architecture
- AWS ECS (Fargate): Containerize the application and use Application Auto Scaling to automatically adjust task counts based on CPU or memory usage.
- AWS EKS: Deploy as containers and scale using the Horizontal Pod Autoscaler (HPA) based on custom CloudWatch metrics.
- Amazon EC2 ASG: Place virtual servers in an Auto Scaling Group with target tracking policies to automatically add or remove instances.

2. Traffic Routing
- Application Load Balancer (ALB): Route incoming traffic evenly across your scaled instances.
- Health Checks: Configure the ALB to monitor the /actuator/health endpoint to ensure traffic only reaches healthy nodes.

3. State Management
- Stateless Services: Remove local session storage so any instance can handle any incoming request.
- Distributed Sessions: Use Spring Session Redis backed by Amazon ElastiCache to manage user sessions externally.
- Distributed Caching: Replace in-memory caches with a shared Amazon ElastiCache cluster.

4. Database Optimization
- Read Replicas: Use Amazon RDS Read Replicas to offload read traffic from the primary database instance
- Connection Pooling: Optimize HikariCP limits in application.properties so scaled instances do not overwhelm database connection limits.

5. Startup Optimization
- GraalVM Native Images: Compile the application to a native executable using Spring Native to slash boot times from seconds to milliseconds.
- Lightweight Containers: Use minimal base images to reduce deployment time when AWS provisions new instances.

=====
For a distributed financial system, one should go for  CP Systems (Consistency + Partition tolerance). This means
* Reject requests during partitions
* Prefer correctness over uptime

There is a tendency of a split brain problem. if partitioned nodes both accept writes:
* Each side develops a different history
* State diverges
* Extremely difficult to reconcile

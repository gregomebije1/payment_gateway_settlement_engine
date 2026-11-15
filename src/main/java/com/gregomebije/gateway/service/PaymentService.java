package com.gregomebije.gateway.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import com.gregomebije.gateway.model.Models.*;
import com.gregomebije.gateway.repository.*;
import com.gregomebije.gateway.integration.LedgerClient.*;

@Service
public class PaymentService {

    private final InboxRepository inboxRepository;
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final OutboxRepository outboxRepository;
    private final RestClient walletRestClient;
    private final LedgerClient ledgerClient;
    private final LedgerTokenService ledgerTokenService;
    private final JsonMapper jsonMapper;
    private final String tenantId;


    public OutboxPoller(
            OutboxRepository outboxRepository,
            AccountRepository accountRepository,
            InboxRepository inboxRepository,
            PaymentRepository paymentRepository,
            @Qualifier("walletRestClient") RestClient walletRestClient,
            LedgerClient ledgerClient,
            LedgerTokenService ledgerTokenService,
            JsonMapper jsonMapper, 
            @Value("${services.ledger.tenant-id}") String tenantId) {
        this.inboxRepository = inboxRepository;
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.outboxRepository = outboxRepository;
        this.walletRestClient = walletRestClient;
        this.ledgerClient = ledgerClient;
        this.ledgerTokenService = ledgerTokenService;
        this.jsonMapper = jsonMapper;
        this.tenantId = tenantId;
    }

    @Transactional
    public String setup() {
        String token = ledgerTokenService.getTokenForTenant(tenantId));
        List<AccountSpec> specs = List.of(
                new CreateAccountRequest("G100", "Processor Receivable", "ASSET", "USD"),
                new CreateAccountRequest("G110", "Gateway Bank", "ASSET", "USD"),
                new CreateAccountRequest("G200", "Merchant Settlement Payable", "LIABILITY", "USD"),
                new CreateAccountRequest("G300", "Processing Fee Revenue", "REVENUE", "USD"),
                new CreateAccountRequest("G400", "Interchange Expense", "EXPENSE", "USD")
        );

        Map<String, Account> accountsByNumber = new LinkedHashMap<>();
        for (CreateAccountRequest spec : specs) {
            try {
                String response = ledgerClient.createAccount(token, spec));
                String rawId = response.substring(response.indexOf(':') + 1).trim();

                Account account = Account.builder()
                        .accountId(UUID.fromString(rawId))
                        .name(spec.name())
                        .type(spec.type())
                        .currency(spec.currency())
                        .active(true)
                        .build();

                accountRepository.save(account);
            } catch (HttpClientErrorException.Unauthorized ex) {
                    //ledgerTokenService.evict(tenantId);
            }
        }
    }
      

    @Transactional
    public String registerUser(CreateTeantRequest request) {
        if (tenantRepository.existsByTenant(request.getName())) {
            return IllegalArguementException("Tenant already exists");
        }
        Tenant tenant = Tenant.builder()
                .tenant(request.getName()) 
                .build();
        tenantRepository.save(tenant);
    }

    @Transactional
    public CaptureResult capturePayment(CreateChargeRequest request) {

        /*
        Inbox inbox = Inbox.builder()
                .messageId(request.getIdempotencyKey())
                .build();
        inboxRepository.save(inbox);

        

        Outbox outbox = Outbox.builder()
                .target('wallet')
                .path("/wallet/v1/webhooks/gateway")
                .idempotencyKey(request.getIdempotencyKey())
                .body()
        */
        /*
        -- [Gateway DB]
        BEGIN;
        INSERT INTO inbox (message_id) VALUES (:dep1) ON CONFLICT DO NOTHING;
        -- 0 rows => duplicate call: ROLLBACK and return the stored response
        INSERT INTO payments (id, amount, fee, status) VALUES (:dep1, 10300, 150, 'CAPTURED');
        SELECT enqueue_entry(:e3, 'gateway', 'Wallet funding captured',
        ARRAY[eline('G100', NULL, 10300, 0), eline('G200', NULL, 0, 10150), eline('G300', NULL, 0, 150)]);
        INSERT INTO outbox (target, path, idempotency_key, body)
        VALUES ('wallet', '/wallet/v1/webhooks/gateway', :msg1,
                jsonb_build_object('type','charge.captured','charge_id',:dep1));
        COMMIT;
            */
        //2. Queue Gateway relay calls Wallet: POST /wallet/v1/webhooks/gateway (Deposit captured)
        //3. POST /ledger/v1/entries e3
        //      e3	A2 Charge captured	Gateway	gateway	G100 $103.00	G200 $101.50; G300 $1.50


        /*
        [ PaymentCaptureService (@Transactional) ]
        ├── 1. INSERT into inbox       (deduplication)
        ├── 2. INSERT into payments    (record payment)
        ├── 3. INSERT into outbox      ("Letter 1: Call Wallet Webhook")
        └── 4. INSERT into outbox      ("Letter 2: Post Ledger Entry")
        └── COMMIT! (Either ALL 4 happen, or NONE happen)

        */
        // 1. Tenant-scoped deduplication check
        int insertedRows = inboxRepository.insertIgnoreConflict(request.getTenantId(), request.getIdempotentKey());
        if (insertedRows == 0) {
            return paymentRepository.findByIdAndTenantId(request.getTenantId(), request.getIdempotentKey())
                    .map(p -> new CaptureResult(p.getId(), p.getStatus()))
                    .orElseThrow(() -> new IllegalStateException("Duplicate inbox key with no payment: " + dep1));
        }

        // 2. Insert captured payment tied to tenant
        //Payment payment = paymentRepository.save(
        //       new Payment(dep1, tenantId, 10300L, 150L, "CAPTURED")
        //);
        Payment payment = Payment.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .tenantId(request.getTenantId())
                .amount(request.getAmount())
                .fee(request.getFee()),
                .status('CAPTURED')
                .build();

        // 3. Stage Wallet Webhook FIRST (tied to tenant)
        var webhookPayload = new GatewayWebhookPayload("charge.captured", request.getIdempotencyKey());
        outboxRepository.save(
                new Outbox(tenantId, "wallet", "/wallet/v1/webhooks/gateway", msg1, webhookPayload)
        );

        /*
                   new CreateAccountRequest("G100", "Processor Receivable", "ASSET", "USD"),
                new CreateAccountRequest("G110", "Gateway Bank", "ASSET", "USD"),
                new CreateAccountRequest("G200", "Merchant Settlement Payable", "LIABILITY", "USD"),
                new CreateAccountRequest("G300", "Processing Fee Revenue", "REVENUE", "USD"),
                new CreateAccountRequest("G400", "Interchange Expense", "EXPENSE", "USD")
        */
        UUID g100AccountId = accountRepository.findByName("Processor Receivable").get().getAccountId();
        UUID g200AccountId = accountRepository.findByName("Merchant Settlement Payable").get().getAccountId();
        UUID g300AccountId = accountRepository.findByName("Processing Fee Revenuee").get().getAccountId();

       // 4. Enqueue Balanced Double-Entry Ledger SECOND using LedgerClient records directly
        var transactionRequest = new CreateTransactionRequest(
                "Wallet funding captured",
                entryId.toString(),
                List.of(
                        new LedgerLine(g100AccountId, LedgerLineType.DEBIT, 10300L, 0L),   // Debit G100: 10300
                        new LedgerLine(g200AccountId, LedgerLineType.CREDIT, 0L, 10150L),  // Credit G200: 10150
                        new LedgerLine(g300AccountId, LedgerLineType.CREDIT, 0L, 150L)     // Credit G300: 150
                )
        );
        outboxRepository.save(
                new OutboxMessage(tenantId, "ledger", "/api/v1/ledgers/transactions", entryId, transactionRequest)
        );


        return new CaptureResult(payment.getId(), payment.getStatus());
    }
}

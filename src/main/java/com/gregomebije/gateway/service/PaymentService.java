package com.gregomebije.gateway.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.ArrayList;
import java.util.Map; 
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gregomebije.gateway.dto.Models.*;
import com.gregomebije.gateway.model.*;
import com.gregomebije.gateway.repository.*;
import com.gregomebije.gateway.integration.LedgerClient;

@Service
public class PaymentService {

    private final OutboxRepository outboxRepository;
    private final AccountRepository accountRepository;
    private final InboxRepository inboxRepository;
    private final PaymentRepository paymentRepository;
    private final TenantRepository tenantRepository;
    private final RestClient walletRestClient;
    private final LedgerClient ledgerClient;
    private final LedgerTokenService ledgerTokenService;
    private final JsonMapper jsonMapper;
    private final String tenantId;

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    public PaymentService(
            OutboxRepository outboxRepository,
            AccountRepository accountRepository,
            InboxRepository inboxRepository,
            PaymentRepository paymentRepository,
            TenantRepository tenantRepository,
            @Qualifier("walletRestClient") RestClient walletRestClient,
            LedgerClient ledgerClient,
            LedgerTokenService ledgerTokenService,
            JsonMapper jsonMapper, 
            @Value("${services.ledger.tenant-id}") String tenantId) {
        this.outboxRepository = outboxRepository;
        this.accountRepository = accountRepository;
        this.inboxRepository = inboxRepository;
        this.paymentRepository = paymentRepository;
        this.tenantRepository = tenantRepository;
        this.walletRestClient = walletRestClient;
        this.ledgerClient = ledgerClient;
        this.ledgerTokenService = ledgerTokenService;
        this.jsonMapper = jsonMapper;
        this.tenantId = tenantId;
    }

    @Transactional
    public void setup() {
        String token = ledgerTokenService.getTokenForTenant(this.tenantId).toString();

        List<CreateAccountRequest> specs = List.of(
            new CreateAccountRequest("G100", "Processor Receivable", AccountType.ASSET, "USD"),
            new CreateAccountRequest("G110", "Gateway Bank", AccountType.ASSET, "USD"),
             
            // A line may carry a sub_ref: the merchant id on G200
            // That gives per-customer balances inside one account (a sub-ledger) 
            // without creating an account per customer.
            new CreateAccountRequest("G200", "Merchant Settlement Payable", AccountType.LIABILITY, "USD"), 
            new CreateAccountRequest("G300", "Processing Fee Revenue", AccountType.REVENUE, "USD"),
            new CreateAccountRequest("G400", "Interchange Expense", AccountType.EXPENSE, "USD")
        );

        List<Account> createdAccounts = new ArrayList<>();

        for (CreateAccountRequest spec : specs) {
            try {
                String response = ledgerClient.createAccount(token, spec);
                // Ensure response parsing strips any JSON formatting if applicable
                String rawId = response.substring(response.indexOf(":") + 1)
                                    .replaceAll("[\"{}]", "")
                                    .trim();

                Account account = Account.builder()
                    .accountId(rawId)
                    .name(spec.name())
                    .type(spec.type())
                    .currency(spec.currency())
                    .isActive(true)
                    .build();

                createdAccounts.add(account);
            } catch (HttpClientErrorException.Unauthorized ex) {
                ledgerTokenService.evict(tenantId);
                logger.error("Unauthorized error in PaymentService.setup() for tenant {}", tenantId, ex);
                throw new IllegalStateException("Failed to authenticate with ledger service", ex);
            }
        }
        accountRepository.saveAll(createdAccounts);
    }
      

    @Transactional
    public String registerUser(CreateTenantRequest request) {
        if (tenantRepository.existsByName(request.tenantName())) {
            throw new IllegalArgumentException("Tenant already exists");
        }
        Tenant tenant = Tenant.builder()
                .name(request.tenantName()) 
                .build();
        tenantRepository.save(tenant);
        return "User registered";
    }

    @Transactional
    public CaptureResult charge(CreateChargeRequest request) {

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
        int insertedRows = inboxRepository.insertIgnoreConflict(
            UUID.fromString(request.tenantId()), request.idempotencyKey());
        if (insertedRows == 0) {
            return paymentRepository.findByTenantIdAndIdempotencyKey(
                UUID.fromString(request.tenantId()), request.idempotencyKey())
                      .map(p -> new CaptureResult(p.getId().toString(), p.getStatus()))
                    .orElseThrow(() -> new IllegalStateException(
                        "Duplicate inbox key with no payment: " + request.idempotencyKey()));
        }

        // 2. Insert captured payment tied to tenant
        //Payment payment = paymentRepository.save(
        //       new Payment(dep1, tenantId, 10300L, 150L, "CAPTURED")
        //);
        Payment payment = Payment.builder()
                .idempotencyKey(request.idempotencyKey())
                .tenantId(UUID.fromString(request.tenantId()))
                .amount(request.amount())
                .fee(request.fee())
                .status("CAPTURED")
                .build();
        paymentRepository.save(payment);

        // 3. Stage Wallet Webhook FIRST (tied to tenant)
        /*var webhookPayload = new GatewayWebhookPayload("charge.captured", request.idempotencyKey());
        outboxRepository.save(
                new Outbox(UUID.fromString(request.tenantId()),
                 "wallet", 
                 "/wallet/v1/webhooks/gateway", 
                 request.idempotencyKey(), 
                 webhookPayload)
        );
        */
        String g100AccountId = accountRepository.findByName("Processor Receivable").get().getAccountId();
        String g200AccountId = accountRepository.findByName("Merchant Settlement Payable").get().getAccountId();
        String g300AccountId = accountRepository.findByName("Processing Fee Revenue").get().getAccountId();

        String dep2 = "d0000000-0000-0000-0000-000000000002";
       // 4. Enqueue Balanced Double-Entry Ledger SECOND using LedgerClient records directly
        var transactionRequest = new CreateTransactionRequest(
                "Wallet funding captured",
                dep2,
                List.of(
                        new LedgerLine(UUID.fromString(g100AccountId), 
                            request.tenantId(), LedgerLineType.DEBIT,  10300L, 0L),  
                        new LedgerLine(UUID.fromString(g200AccountId), 
                            request.tenantId(),  LedgerLineType.CREDIT, 0L, 10150L),  
                        new LedgerLine(UUID.fromString(g300AccountId), 
                            request.tenantId(), LedgerLineType.CREDIT, 0L, 150L) 
                )
        );
        //Todo: I need to match this.tenantId and request.tenantId()
        //this.tenantId = 'payment_gateway_tenant' but request.tenantId = "a UUID"
        outboxRepository.save(
                new Outbox(UUID.fromString(request.tenantId()), 
                    "ledger", "/api/v1/ledgers/transactions", dep2, transactionRequest)
               
        );
        
        return new CaptureResult(payment.getId().toString(), payment.getStatus());
    }
}

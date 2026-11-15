package com.gregomebije.gateway.utilities;

import com.gregomebije.gateway.repository.OutboxRepository;
import com.gregomebije.gateway.integration.LedgerClient;
import com.gregomebije.gateway.integration.LedgerClient.CreateTransactionRequest;
import com.gregomebije.gateway.integration.LedgerClient.LedgerLine;
import com.gregomebije.gateway.integration.LedgerClient.LedgerLineType;
import com.gregomebije.gateway.integration.LedgerTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository outboxRepository;
    private final RestClient walletRestClient;
    private final LedgerClient ledgerClient;
    private final LedgerTokenService ledgerTokenService;
    private final JsonMapper jsonMapper;

    public OutboxPoller(
            OutboxRepository outboxRepository,
            @Qualifier("walletRestClient") RestClient walletRestClient,
            LedgerClient ledgerClient,
            LedgerTokenService ledgerTokenService,
            JsonMapper jsonMapper) {
        this.outboxRepository = outboxRepository;
        this.walletRestClient = walletRestClient;
        this.ledgerClient = ledgerClient;
        this.ledgerTokenService = ledgerTokenService;
        this.jsonMapper = jsonMapper;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:500}") //500 milliseconds
    @Transactional
    public void pollAndDispatch() {
        List<Outbox> batch = outboxRepository.claimBatchForProcessing(BATCH_SIZE);
        for (Outbox message : batch) {
            try {
                dispatch(message);
                message.markSent();     // <--- 1. Changes status to SENT
            } catch (Exception ex) {
                log.warn("Outbox dispatch failed id={} tenant={} target={} path={}: {}",
                        message.getId(), message.getTenantId(), message.getTarget(), message.getPath(), ex.getMessage());
                message.markFailed(MAX_ATTEMPTS); //<--- 2. Increments attempts & sets DEAD_LETTER (or keeps PENDING)
            }
        }
        //Why Isn't outboxRepository.save(message) Called?
        //because of a JPA / Hibernate feature called Managed Entity Dirty Checking:
        /* Calling outboxRepository.save(message) explicitly wouldn't hurt 
          anything, because Spring Data JPA's save() simply calls 
          entityManager.merge(), which is a no-op on an already-managed 
          entity—it's just redundant inside a @Transactional method)
        */

    }

    private void dispatch(Outbox message) {
        switch (message.getTarget()) {
            case "wallet" -> dispatchWebhook(message);
            case "ledger" -> dispatchToLedger(message);
            default -> throw new IllegalArgumentException("Unknown outbox target: " + message.getTarget());
        }
    }

    private void dispatchWebhook(OutboxMessage message) {
        walletRestClient.post()
                .uri(message.getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", message.getIdempotencyKey().toString())
                .header("X-Tenant-Id", message.getTenantId().toString())
                .body(message.getBody())
                .retrieve()
                .toBodilessEntity();
    }

    private void dispatchToLedger(OutboxMessage message) {
        CreateTransactionRequest request = jsonMapper.convertValue(
                message.getBody(),
                CreateTransactionRequest.class
        );

        UUID tenantId = message.getTenantId();
        String token = ledgerTokenService.getTokenForTenant(tenantId);

        try {
            ledgerClient.postTransaction(token, request);
        } catch (HttpClientErrorException.Unauthorized ex) {
            ledgerTokenService.evict(tenantId);
            String refreshedToken = ledgerTokenService.getTokenForTenant(tenantId);
            ledgerClient.postTransaction(refreshedToken, request);
        }
    }
}
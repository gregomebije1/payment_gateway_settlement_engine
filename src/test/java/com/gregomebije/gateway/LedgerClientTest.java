package com.gregomebije.gateway;

import com.gregomebije.gateway.integration.LedgerClient;
import com.gregomebije.gateway.integration.LedgerClient.CreateAccountRequest;
import com.gregomebije.gateway.integration.LedgerClient.CreateTenantRequest;
import com.gregomebije.gateway.integration.LedgerClient.CreateTransactionRequest;
import com.gregomebije.gateway.integration.LedgerClient.CreateUserRequest;
import com.gregomebije.gateway.integration.LedgerClient.LedgerLine;
import com.gregomebije.gateway.integration.LedgerClient.LedgerLineType;
import com.gregomebije.gateway.integration.LedgerClient.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end test: 
 * Requires Docker running locally.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // WebClientConfig's @Value("${ledger.service.url}") points back at this same app.
                "ledger.service.url=http://localhost:8081",
                "wallet.service.url=http://localhost:8080" // unused here, but must resolve
        })
@Testcontainers
class LedgerClientTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    LedgerClient ledgerClient;

    private String email;
    private String tenantId;
    private String token;

    /** Fresh user + tenant + token for every test, so tests never collide with each other. */
    @BeforeEach
    void onboard() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        email = "user-" + suffix + "@mangital.com";
        tenantId = "tenant-" + suffix;

        ledgerClient.registerUser(new CreateUserRequest(email, "password", "John", "Doe"));
        // Note: CreateTenantRequest field order is (email, tenantId, tenantName)
        ledgerClient.registerTenant(new CreateTenantRequest(email, tenantId, "Mangital ltd"));
        token = ledgerClient.login(new LoginRequest(tenantId, email, "password"));
    }

    // ---------- Happy path ----------

    @Test
    @DisplayName("onboarding -> login -> create accounts -> post balanced transaction")
    void fullFlow() {
        assertThat(token).isNotBlank();

        UUID aliceId = createAccount("Alice wallet");
        UUID fundingId = createAccount("Funding account");

        String result = ledgerClient.postTransaction(
                token,
                balancedTx("fund-" + UUID.randomUUID(), fundingId, aliceId)
        );

        assertThat(result).startsWith("Ledger entry balance block recorded successfully:");
    }

    // ---------- Onboarding errors ----------

    @Test
    @DisplayName("registerUser -> duplicate email -> 400")
    void registerUser_duplicateEmail() {
        assertThatThrownBy(() ->
                ledgerClient.registerUser(new CreateUserRequest(email, "password", "John", "Doe")))
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getResponseBodyAsString()).contains("Email already exist.");
                });
    }

    @Test
    @DisplayName("login -> wrong password -> 4xx")
    void login_wrongPassword() {
        assertThatThrownBy(() ->
                ledgerClient.login(new LoginRequest(tenantId, email, "wrong-password")))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        ex -> assertThat(ex.getStatusCode().is4xxClientError()).isTrue());
    }

    // ---------- Security ----------

    @Test
    @DisplayName("createAccount -> no token -> 401")
    void createAccount_noToken() {
        assertThatThrownBy(() ->
                ledgerClient.createAccount(null, newAccount("No token account")))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
    }

    // ---------- Ledger rules ----------

    @Test
    @DisplayName("postTransaction -> unbalanced -> 400")
    void postTransaction_unbalanced() {
        UUID aliceId = createAccount("Alice wallet");
        UUID fundingId = createAccount("Funding account");

        CreateTransactionRequest unbalanced = new CreateTransactionRequest(
                "Unbalanced",
                "unbalanced-" + UUID.randomUUID(),
                List.of(
                        new LedgerLine(fundingId, LedgerLineType.DEBIT, 100_000L, 0L),
                        new LedgerLine(aliceId, LedgerLineType.CREDIT, 0L, 50_000L)
                )
        );

        assertThatThrownBy(() -> ledgerClient.postTransaction(token, unbalanced))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("postTransaction -> same idempotency key twice -> 409")
    void postTransaction_idempotencyConflict() {
        UUID aliceId = createAccount("Alice wallet");
        UUID fundingId = createAccount("Funding account");
        CreateTransactionRequest tx = balancedTx("dup-" + UUID.randomUUID(), fundingId, aliceId);

        ledgerClient.postTransaction(token, tx); // first call succeeds

        assertThatThrownBy(() -> ledgerClient.postTransaction(token, tx))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
    }

    // ---------- Helpers ----------

    private CreateAccountRequest newAccount(String name) {
        String number = String.valueOf(1000 + (int) (Math.random() * 9000))
                + UUID.randomUUID().toString().substring(0, 4);
        return new CreateAccountRequest(number, name, "ASSET", "USD");
    }

    private UUID createAccount(String name) {
        String response = ledgerClient.createAccount(token, newAccount(name));
        assertThat(response).startsWith("Account created:");
        String rawId = response.substring(response.indexOf(':') + 1).trim();
        return UUID.fromString(rawId);
    }

    private static CreateTransactionRequest balancedTx(String idempotencyKey, UUID debitAccountId,
                                                       UUID creditAccountId) {
        return new CreateTransactionRequest(
                "Fund Alice",
                idempotencyKey,
                List.of(
                        new LedgerLine(debitAccountId, LedgerLineType.DEBIT, 100_000L, 0L),
                        new LedgerLine(creditAccountId, LedgerLineType.CREDIT, 0L, 100_000L)
                )
        );
    }
}
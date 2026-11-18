package com.gregomebije.gateway.integration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import com.gregomebije.gateway.dto.Models.*;

/** RestClient-based client for the ledger API. */
@Component
public class LedgerClient {

    private final RestClient ledgerRestClient;

    public LedgerClient(@Qualifier("ledgerRestClient") RestClient ledgerRestClient) {
        this.ledgerRestClient = ledgerRestClient;
    }

    public String registerUser(CreateUserRequest request) {
        return post("/api/v1/onboarding/user", request, null);
    }

    public String registerTenant(CreateTenantRequest request) {
        return post("/api/v1/onboarding/tenant", request, null);
    }

    public String login(LoginRequest request) {
        LoginResponse response = ledgerRestClient.post()
                .uri("/api/v1/onboarding/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(LoginResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Login response did not contain an accessToken");
        }
        return response.accessToken();
    }

    public String createAccount(String token, CreateAccountRequest request) {
        return post("/api/v1/ledgers/accounts", request, token);
    }

    public String postTransaction(String token, CreateTransactionRequest request) {
        return post("/api/v1/ledgers/transactions", request, token);
    }

    private String post(String uri, Object body, String token) {
        RestClient.RequestBodySpec spec = ledgerRestClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON);

        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        return spec.body(body)
                .retrieve()
                .body(String.class);
    }
}

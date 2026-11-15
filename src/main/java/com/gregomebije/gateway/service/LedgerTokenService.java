package com.gregomebije.gateway.service;

import com.gregomebije.gateway.integration.LedgerClient.LoginRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LedgerTokenService {

    private record CachedToken(String token, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private final LedgerClient ledgerClient;
    private final String serviceEmail;
    private final String servicePassword;
    private final Duration tokenTtl;

    private final ConcurrentHashMap<UUID, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public LedgerTokenService(
            LedgerClient ledgerClient,
            @Value("${services.ledger.email}") String serviceEmail,
            @Value("${services.ledger.password}") String servicePassword,
            @Value("${services.ledger.token-ttl-minutes:50}") long ttlMinutes) {
        this.ledgerClient = ledgerClient;
        this.serviceEmail = serviceEmail;
        this.servicePassword = servicePassword;
        this.tokenTtl = Duration.ofMinutes(ttlMinutes);
    }

    public String getTokenForTenant(UUID tenantId) {
        CachedToken cached = tokenCache.get(tenantId);
        if (cached != null && !cached.isExpired()) {
            return cached.token();
        }

        // Atomically refresh token for this specific tenant without blocking other tenants
        return tokenCache.compute(tenantId, (id, existing) -> {
            if (existing != null && !existing.isExpired()) {
                return existing;
            }
            String freshToken = ledgerClient.login(
                    new LoginRequest(id.toString(), serviceEmail, servicePassword)
            );
            return new CachedToken(freshToken, Instant.now().plus(tokenTtl));
        }).token();
    }

    public void evict(UUID tenantId) {
        tokenCache.remove(tenantId);
    }
}

package com.gregomebije.gateway.idempotency;

public interface IdempotencyService {
    boolean acquire(String key);
}

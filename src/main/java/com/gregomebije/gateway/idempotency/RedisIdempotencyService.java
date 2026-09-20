package com.gregomebije.gateway.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisIdempotencyService implements IdempotencyService {
    private final StringRedisTemplate redis;

    public RedisIdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean acquire(String key) {
        return Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(
                "idempotency:" + key, "PROCESSING", Duration.ofMinutes(15)
            )
        );
    }
}

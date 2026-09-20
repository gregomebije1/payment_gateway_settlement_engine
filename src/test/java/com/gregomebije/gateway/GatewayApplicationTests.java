package com.gregomebije.gateway;

import org.junit.jupiter.api.Test;

// Extending BaseIntegrationTest automatically fires up your Postgres, Redis, and Kafka containers
class GatewayApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // This acts as a smoke test ensuring the application boots up 
        // and links to all three containers with zero configuration issues.
    }
}

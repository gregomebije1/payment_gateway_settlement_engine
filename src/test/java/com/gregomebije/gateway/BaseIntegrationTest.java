package com.gregomebije.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    // 1. Define the PostgreSQL container
    protected static final PostgreSQLContainer<?> postgresContainer = 
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("gateway")
                    .withUsername("gateway")
                    .withPassword("gateway");

    // 2. Define the Redis container using the GenericContainer wrapper
    protected static final GenericContainer<?> redisContainer = 
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    // 3. Define the Kafka container
    protected static final KafkaContainer kafkaContainer = 
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    static {
        // Start all containers in parallel to minimize test startup delay
        Startables.deepStart(postgresContainer, redisContainer, kafkaContainer).join();
    }

    // 4. Overwrite application properties with live runtime container endpoints
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Database connection values
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);

        // Redis connection values
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));

        // Kafka connection values
        registry.add("spring.kafka.bootstrap-servers", kafkaContainer::getBootstrapServers);
    }
}

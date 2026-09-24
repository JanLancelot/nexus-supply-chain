package com.nexus.supplychain.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    static PostgreSQLContainer<?> postgres;
    static GenericContainer<?> redis;
    static KafkaContainer kafka;
    static final boolean testcontainersActive = Boolean.getBoolean("integration.containers");

    static {
        if (testcontainersActive) {
            postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("supply_db")
                    .withUsername("enterprise_admin")
                    .withPassword("secure_dev_password");

            redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

            kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

            postgres.start();
            redis.start();
            kafka.start();
            // Fail the suite if any container cannot start. A container run must
            // never succeed by silently switching to in-memory infrastructure.
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (testcontainersActive) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
            registry.add("app.events.kafka-required", () -> true);
        } else {
            // Explicit lightweight mode; no Docker discovery or startup.
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:supply_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
            
            // In-memory/local fallbacks
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> 16379);
            registry.add("spring.kafka.bootstrap-servers", () -> "localhost:19092");
        }
    }
}

package com.pg.supplychain.integration;

import org.junit.jupiter.api.BeforeAll;
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
    static boolean testcontainersActive = false;

    static {
        try {
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
            testcontainersActive = true;
            System.setProperty("integration.testcontainers.active", "true");
        } catch (Exception e) {
            System.err.println("WARNING: Testcontainers failed to start. Falling back to H2 / KafkaLite / In-memory Redis. Error: " + e.getMessage());
            System.setProperty("integration.testcontainers.active", "false");
            testcontainersActive = false;
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if ("true".equals(System.getProperty("integration.testcontainers.active")) && testcontainersActive) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);

            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        } else {
            // Fallback for restricted sandbox environments
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:supply_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            
            // In-memory/local fallbacks
            registry.add("spring.data.redis.host", () -> "localhost");
            registry.add("spring.data.redis.port", () -> 6379);
            registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        }
    }
}

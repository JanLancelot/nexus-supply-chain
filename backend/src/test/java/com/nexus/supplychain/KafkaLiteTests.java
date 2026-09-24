package com.nexus.supplychain;

import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.repository.AuditLogRepository;
import com.nexus.supplychain.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class KafkaLiteTests {

    @Autowired
    private KafkaLiteBroker broker;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private com.nexus.supplychain.service.ProductService productService;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired(required = false)
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws InterruptedException {
        // Allow background thread to quiescent
        Thread.sleep(500);

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(java.util.List.of(
                        "kafka_topic_product-events",
                        "kafka_topic_order-events",
                        "kafka_topic_audit-events",
                        "kafka_topic_category-events",
                        "kafka_topic_warehouse-events"
                ));
            } catch (Exception e) {
                // Redis not available in test environment — safe to ignore
            }
        }
    }

    @Test
    void testBrokerPublishAndSubscribe() throws InterruptedException {
        String topic = "test-topic-" + UUID.randomUUID();
        String payload = "Hello Kafka-Lite!";
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        broker.subscribe(topic, message -> {
            receivedPayload.set(message);
            latch.countDown();
        });

        broker.send(topic, payload);

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Callback should be triggered within 5 seconds");
        // Payload gets serialized as JSON by the broker
        assertNotNull(receivedPayload.get());
        assertTrue(receivedPayload.get().contains("Hello Kafka-Lite!"));
    }

    @Test
    void auditLoggingIsCompleteBeforeReturning() {
        long initialCount = auditLogRepository.count();
        auditService.logChange("Product", UUID.randomUUID(), "TEST_ACTION", null, "New Value");
        assertEquals(initialCount + 1, auditLogRepository.count());
    }

    @Test
    void testRedisCacheAndEviction() throws InterruptedException {
        // Clear cache first to ensure clean state
        var cache = cacheManager.getCache("products");
        if (cache != null) {
            cache.clear();
        }

        // Call Service to populate cache
        productService.getAllProducts();

        assertNotNull(cache);
        if (cache instanceof com.nexus.supplychain.config.ResilientCacheManager.ResilientCache resilient && !resilient.isAvailable()) {
            assertNull(cache.get("all"), "Redis outage must disable caches instead of retaining local stock values");
            return;
        }
        assertNotNull(cache.get("all"), "Cache should be populated after retrieval");

        // Publish a product event indicating update
        broker.send("product-events", com.nexus.supplychain.kafkalite.event.ProductEvent.builder()
                .productId(UUID.randomUUID())
                .action("CREATED")
                .build());

        // Wait for background worker to process cache eviction
        int retries = 50;
        while (cache.get("all") != null && retries > 0) {
            Thread.sleep(100);
            retries--;
        }

        assertNull(cache.get("all"), "Cache should be evicted by the background event listener");
    }
    @Test
    void auditEvidenceIsVisibleInsideMutationTransactionAndRollsBackWithIt() {
        UUID entityId = UUID.randomUUID();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        long before = auditLogRepository.count();
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            auditService.logChange("Product", entityId, "ROLLBACK_TEST", null, java.util.Map.of("quantity", 5));
            assertEquals(before + 1, auditLogRepository.count(), "Audit evidence must exist before commit");
            throw new IllegalStateException("mutation failed");
        }));
        assertEquals(before, auditLogRepository.count(), "Audit must roll back with the failed mutation");
    }
}

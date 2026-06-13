package com.pg.supplychain;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.repository.AuditLogRepository;
import com.pg.supplychain.service.AuditService;
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
    private com.pg.supplychain.service.ProductService productService;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws InterruptedException {
        // Allow background thread to quiescent
        Thread.sleep(500);

        redisTemplate.delete(java.util.List.of(
                "kafka_topic_product-events",
                "kafka_topic_order-events",
                "kafka_topic_audit-events",
                "kafka_topic_category-events",
                "kafka_topic_warehouse-events"
        ));
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
    void testAsynchronousAuditLogging() throws InterruptedException {
        long initialCount = auditLogRepository.count();
        UUID entityId = UUID.randomUUID();

        // Perform audit log change
        auditService.logChange("Product", entityId, "TEST_ACTION", null, "New Value");

        // Assert that logging is asynchronous: it might not be written immediately to DB
        // So we poll for up to 5 seconds to wait for background consumer to save it
        int retries = 50;
        long newCount = initialCount;
        while (retries > 0) {
            newCount = auditLogRepository.count();
            if (newCount > initialCount) {
                break;
            }
            Thread.sleep(100);
            retries--;
        }

        assertTrue(newCount > initialCount, "Audit log should be written asynchronously by background listener");
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


        // Verify cache is populated
        System.out.println("DEBUG: Cache object = " + cache);
        if (cache != null) {
            try {
                org.springframework.cache.Cache.ValueWrapper wrapper = cache.get("all");
                System.out.println("DEBUG: ValueWrapper = " + wrapper);
                if (wrapper != null) {
                    System.out.println("DEBUG: Cached value = " + wrapper.get());
                } else {
                    System.out.println("DEBUG: ValueWrapper is null!");
                }
            } catch (Exception e) {
                System.out.println("DEBUG: Exception during cache.get('all')");
                e.printStackTrace();
            }
        }
        assertNotNull(cache.get("all"), "Cache should be populated after retrieval");

        // Publish a product event indicating update
        broker.send("product-events", com.pg.supplychain.kafkalite.event.ProductEvent.builder()
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
}

package com.nexus.supplychain.integration;

import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BrokerDeliveryIntegrationTest extends BaseIntegrationTest {
    @Autowired private KafkaLiteBroker broker;

    @Test
    void failedBusinessHandlerRetriesWithoutRepeatingSuccessfulSubscribers() throws InterruptedException {
        String topic = "retry-test-" + UUID.randomUUID();
        java.util.concurrent.atomic.AtomicInteger failingAttempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger successfulAttempts = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch successful = new CountDownLatch(1);
        broker.subscribe(topic, "successful", message -> {
            successfulAttempts.incrementAndGet();
            successful.countDown();
        });
        broker.subscribe(topic, "retrying", message -> {
            if (failingAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("transient database failure");
            }
            recovered.countDown();
        });
        broker.send(topic, "recoverable");
        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        assertTrue(successful.await(15, TimeUnit.SECONDS));
        assertEquals(2, failingAttempts.get());
        assertEquals(1, successfulAttempts.get());
    }

    @Test
    void containerModeUsesKafka() {
        if (testcontainersActive) {
            assertEquals("KAFKA_UP", broker.healthStatus());
            assertTrue(broker.isRequired());
        } else {
            assertEquals("MEMORY_FALLBACK", broker.healthStatus());
        }
    }
}

package com.nexus.supplychain.kafkalite;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RedisKafkaLiteBrokerTest {
    @Test
    void waitsForAllStartupSubscribersBeforeConsumingQueuedEvents() throws Exception {
        RedisKafkaLiteBroker broker = new RedisKafkaLiteBroker(null, JsonMapper.builder().build());
        try {
            CountDownLatch firstHandler = new CountDownLatch(1);
            CountDownLatch secondHandler = new CountDownLatch(1);
            broker.send("startup-test", "queued before registration");
            broker.subscribe("startup-test", message -> firstHandler.countDown());

            assertFalse(firstHandler.await(200, TimeUnit.MILLISECONDS),
                    "Queued events must wait for listener registration to finish");
            broker.subscribe("startup-test", message -> secondHandler.countDown());
            broker.afterSingletonsInstantiated();

            assertTrue(firstHandler.await(2, TimeUnit.SECONDS));
            assertTrue(secondHandler.await(2, TimeUnit.SECONDS));
        } finally {
            broker.shutdown();
        }
    }
}

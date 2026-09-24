package com.nexus.supplychain.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CacheConfigTest {
    @Test
    void unavailableRedisDoesNotRetainInstanceLocalValues() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("Unavailable in test"));
        var manager = new CacheConfig().cacheManager(factory, JsonMapper.builder().build());
        var cache = manager.getCache("products");
        assertNotNull(cache);
        cache.put("all", "stale inventory");
        assertNull(cache.get("all"));
        AtomicInteger databaseReads = new AtomicInteger();
        assertEquals(1, cache.get("all", databaseReads::incrementAndGet));
        assertEquals(2, cache.get("all", databaseReads::incrementAndGet));
    }

    @Test
    void runtimeOutageBypassesReadsAndClearsStaleValuesBeforeRecovering() {
        Cache redis = spy(new ConcurrentMapCache("products"));
        var cache = new ResilientCacheManager.ResilientCache(redis, 0);
        cache.put("all", "before mutation");
        doThrow(new RedisConnectionFailureException("offline")).when(redis).clear();
        cache.clear();
        assertFalse(cache.isAvailable());
        assertEquals("database result", cache.get("all", () -> "database result"));
        assertEquals("before mutation", redis.get("all").get());
        doCallRealMethod().when(redis).clear();
        assertEquals("after mutation", cache.get("all", () -> "after mutation"));
        assertTrue(cache.isAvailable());
        assertEquals("after mutation", redis.get("all").get());
    }

    @Test
    void inFlightLoaderCannotPopulateCacheAfterOutageRecovery() throws Exception {
        Cache redis = spy(new ConcurrentMapCache("products"));
        var cache = new ResilientCacheManager.ResilientCache(redis, 0);
        java.util.concurrent.CountDownLatch loaded = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowWrite = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var oldRead = executor.submit(() -> cache.get("all", () -> {
                loaded.countDown();
                assertTrue(allowWrite.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return "before mutation";
            }));
            assertTrue(loaded.await(5, java.util.concurrent.TimeUnit.SECONDS));
            doThrow(new RedisConnectionFailureException("offline")).when(redis).clear();
            cache.clear();
            doCallRealMethod().when(redis).clear();
            assertEquals("after mutation", cache.get("all", () -> "after mutation"));
            allowWrite.countDown();
            assertEquals("before mutation", oldRead.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("after mutation", cache.get("all").get());
        } finally {
            allowWrite.countDown();
            executor.shutdownNow();
        }
    }
}

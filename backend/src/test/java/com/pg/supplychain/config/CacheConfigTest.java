package com.pg.supplychain.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.support.NoOpCacheManager;
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

        assertInstanceOf(NoOpCacheManager.class, manager);
        assertNotNull(cache);
        cache.put("all", "stale inventory");
        assertNull(cache.get("all"));
        AtomicInteger databaseReads = new AtomicInteger();
        assertEquals(1, cache.get("all", databaseReads::incrementAndGet));
        assertEquals(2, cache.get("all", databaseReads::incrementAndGet));
    }
}

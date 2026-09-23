package com.pg.supplychain.config;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
@Slf4j
public class CacheConfig {

    @Bean
    public RedisCacheConfiguration cacheConfiguration(ObjectMapper objectMapper) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new JacksonLiteRedisSerializer(objectMapper)));
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        try {
            // Test the connection before building the Redis cache manager
            connectionFactory.getConnection().close();
            log.info("CacheConfig: Redis is available. Using RedisCacheManager.");
            return RedisCacheManager.builder(connectionFactory)
                    .cacheDefaults(cacheConfiguration(objectMapper))
                    .withCacheConfiguration("products", cacheConfiguration(objectMapper).entryTtl(Duration.ofSeconds(30)))
                    .withCacheConfiguration("analytics", cacheConfiguration(objectMapper).entryTtl(Duration.ofSeconds(15)))
                    .build();
        } catch (Exception e) {
            log.warn("Redis is not available ({}). Caching is disabled until the application restarts.", e.getMessage());
            // A local cache cannot observe events consumed by another application instance.
            return new NoOpCacheManager();
        }
    }
}

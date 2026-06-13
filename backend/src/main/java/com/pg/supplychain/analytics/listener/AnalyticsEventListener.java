package com.pg.supplychain.analytics.listener;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsEventListener {

    private final KafkaLiteBroker broker;
    private final CacheManager cacheManager;

    @PostConstruct
    public void registerListeners() {
        broker.subscribe("product-events", message -> evictAnalyticsCache());
        broker.subscribe("order-events", message -> evictAnalyticsCache());
        log.info("AnalyticsEventListener: Subscribed to product-events and order-events topics to invalidate analytics cache.");
    }

    private void evictAnalyticsCache() {
        var cache = cacheManager.getCache("analytics");
        if (cache != null) {
            cache.clear();
            log.info("AnalyticsEventListener: Evicted 'analytics' cache.");
        }
    }
}

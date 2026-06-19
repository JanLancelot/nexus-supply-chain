package com.pg.supplychain.service;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictionListener {

    private final KafkaLiteBroker broker;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingEvictions = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerListeners() {
        broker.subscribe("product-events", this::handleProductEvent);
        broker.subscribe("order-events", this::handleOrderEvent);
        broker.subscribe("category-events", this::handleCategoryEvent);
        broker.subscribe("warehouse-events", this::handleWarehouseEvent);
        log.info("CacheEvictionListener: Subscribed to product, order, category, and warehouse event topics.");
    }

    private void handleProductEvent(String messageJson) {
        try {
            ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
            log.info("CacheEvictionListener: Received ProductEvent: action={}, productId={}", event.getAction(), event.getProductId());
            // Skip evicting products cache for STOCK_ADJUSTED events since name/description are unaffected
            if (!"STOCK_ADJUSTED".equalsIgnoreCase(event.getAction())) {
                evictCache("products");
            }
            // Do NOT evict analytics cache; rely on its 2-minute natural TTL to prevent query spikes under load
        } catch (Exception e) {
            log.error("CacheEvictionListener: Failed to process ProductEvent message", e);
        }
    }

    private void handleOrderEvent(String messageJson) {
        try {
            OrderEvent event = objectMapper.readValue(messageJson, OrderEvent.class);
            log.info("CacheEvictionListener: Received OrderEvent: status={}, orderId={}", event.getStatus(), event.getOrderId());
            // Do NOT evict analytics cache; rely on its 2-minute natural TTL to prevent query spikes under load
            if ("DELIVERED".equalsIgnoreCase(event.getStatus())) {
                evictCache("products");
            }
        } catch (Exception e) {
            log.error("CacheEvictionListener: Failed to process OrderEvent message", e);
        }
    }

    private void handleCategoryEvent(String messageJson) {
        try {
            CategoryEvent event = objectMapper.readValue(messageJson, CategoryEvent.class);
            log.info("CacheEvictionListener: Received CategoryEvent: action={}, categoryId={}", event.getAction(), event.getCategoryId());
            evictCache("categories");
        } catch (Exception e) {
            log.error("CacheEvictionListener: Failed to process CategoryEvent message", e);
        }
    }

    private void handleWarehouseEvent(String messageJson) {
        try {
            WarehouseEvent event = objectMapper.readValue(messageJson, WarehouseEvent.class);
            log.info("CacheEvictionListener: Received WarehouseEvent: action={}, warehouseId={}", event.getAction(), event.getWarehouseId());
            evictCache("warehouses");
        } catch (Exception e) {
            log.error("CacheEvictionListener: Failed to process WarehouseEvent message", e);
        }
    }

    private void evictCache(String cacheName) {
        ScheduledFuture<?> future = pendingEvictions.put(cacheName, scheduler.schedule(() -> {
            var cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.info("CacheEvictionListener: Evicted '{}' cache after debounce.", cacheName);
            }
            pendingEvictions.remove(cacheName);
        }, 1, TimeUnit.SECONDS));

        if (future != null) {
            future.cancel(false);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}

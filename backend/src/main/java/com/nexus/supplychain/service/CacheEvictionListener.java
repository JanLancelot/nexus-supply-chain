package com.nexus.supplychain.service;

import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class CacheEvictionListener {

    private final KafkaLiteBroker broker;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void registerListeners() {
        broker.subscribe("product-events", "cache-eviction", this::handleProductEvent);
        broker.subscribe("order-events", "cache-eviction", this::handleOrderEvent);
        broker.subscribe("category-events", "cache-eviction", this::handleCategoryEvent);
        broker.subscribe("warehouse-events", "cache-eviction", this::handleWarehouseEvent);
        log.info("CacheEvictionListener: Subscribed to product, order, category, and warehouse event topics.");
    }

    private void handleProductEvent(String messageJson) {
        try {
            ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
            log.info("CacheEvictionListener: Received ProductEvent: action={}, productId={}", event.getAction(), event.getProductId());
            evictCache("products");
            evictCache("analytics");
        } catch (Exception e) {
            throw new IllegalStateException("CacheEvictionListener: Failed to process ProductEvent message", e);
        }
    }

    private void handleOrderEvent(String messageJson) {
        try {
            OrderEvent event = objectMapper.readValue(messageJson, OrderEvent.class);
            log.info("CacheEvictionListener: Received OrderEvent: status={}, orderId={}", event.getStatus(), event.getOrderId());
            evictCache("analytics");
            if ("DELIVERED".equalsIgnoreCase(event.getStatus())) {
                evictCache("products");
            }
        } catch (Exception e) {
            throw new IllegalStateException("CacheEvictionListener: Failed to process OrderEvent message", e);
        }
    }

    private void handleCategoryEvent(String messageJson) {
        try {
            CategoryEvent event = objectMapper.readValue(messageJson, CategoryEvent.class);
            log.info("CacheEvictionListener: Received CategoryEvent: action={}, categoryId={}", event.getAction(), event.getCategoryId());
            evictCache("categories");
            evictCache("products");
        } catch (Exception e) {
            throw new IllegalStateException("CacheEvictionListener: Failed to process CategoryEvent message", e);
        }
    }

    private void handleWarehouseEvent(String messageJson) {
        try {
            WarehouseEvent event = objectMapper.readValue(messageJson, WarehouseEvent.class);
            log.info("CacheEvictionListener: Received WarehouseEvent: action={}, warehouseId={}", event.getAction(), event.getWarehouseId());
            evictCache("warehouses");
            evictCache("products");
            evictCache("analytics");
        } catch (Exception e) {
            throw new IllegalStateException("CacheEvictionListener: Failed to process WarehouseEvent message", e);
        }
    }

    private void evictCache(String cacheName) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}

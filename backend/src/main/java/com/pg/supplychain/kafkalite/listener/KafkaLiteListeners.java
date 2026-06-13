package com.pg.supplychain.kafkalite.listener;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.*;
import com.pg.supplychain.model.AuditLog;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.AuditLogRepository;
import com.pg.supplychain.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaLiteListeners {

    private final KafkaLiteBroker broker;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void registerListeners() {
        broker.subscribe("audit-events", this::handleAuditEvent);
        broker.subscribe("product-events", this::handleProductEvent);
        broker.subscribe("order-events", this::handleOrderEvent);
        broker.subscribe("category-events", this::handleCategoryEvent);
        broker.subscribe("warehouse-events", this::handleWarehouseEvent);
        log.info("KafkaLiteListeners: Subscribed to audit, product, order, category, and warehouse topics.");
    }

    private void handleAuditEvent(String messageJson) {
        try {
            AuditEvent event = objectMapper.readValue(messageJson, AuditEvent.class);
            log.info("KafkaLiteListeners: Received AuditEvent: action={}", event.getAction());

            User user = null;
            if (event.getActorId() != null) {
                user = userRepository.findById(event.getActorId()).orElse(null);
            }

            String oldStr = null;
            String newStr = null;
            if (event.getOldValue() != null) {
                oldStr = objectMapper.writeValueAsString(event.getOldValue());
            }
            if (event.getNewValue() != null) {
                newStr = objectMapper.writeValueAsString(event.getNewValue());
            }

            AuditLog logEntry = AuditLog.builder()
                    .user(user)
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId())
                    .action(event.getAction())
                    .oldValue(oldStr)
                    .newValue(newStr)
                    .createdAt(OffsetDateTime.now())
                    .build();

            auditLogRepository.save(logEntry);
            log.debug("KafkaLiteListeners: Saved audit log asynchronously for action={}", event.getAction());
        } catch (Exception e) {
            log.error("KafkaLiteListeners: Failed to process AuditEvent message", e);
        }
    }

    private void handleProductEvent(String messageJson) {
        try {
            ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
            log.info("KafkaLiteListeners: Received ProductEvent: action={}, productId={}", event.getAction(), event.getProductId());

            evictCache("products");
        } catch (Exception e) {
            log.error("KafkaLiteListeners: Failed to process ProductEvent message", e);
        }
    }

    private void handleOrderEvent(String messageJson) {
        try {
            OrderEvent event = objectMapper.readValue(messageJson, OrderEvent.class);
            log.info("KafkaLiteListeners: Received OrderEvent: status={}, orderId={}", event.getStatus(), event.getOrderId());

            // If order was delivered, product stock values changed, so invalidate products cache
            if ("DELIVERED".equalsIgnoreCase(event.getStatus())) {
                evictCache("products");
            }
        } catch (Exception e) {
            log.error("KafkaLiteListeners: Failed to process OrderEvent message", e);
        }
    }

    private void handleCategoryEvent(String messageJson) {
        try {
            CategoryEvent event = objectMapper.readValue(messageJson, CategoryEvent.class);
            log.info("KafkaLiteListeners: Received CategoryEvent: action={}, categoryId={}", event.getAction(), event.getCategoryId());

            evictCache("categories");
        } catch (Exception e) {
            log.error("KafkaLiteListeners: Failed to process CategoryEvent message", e);
        }
    }

    private void handleWarehouseEvent(String messageJson) {
        try {
            WarehouseEvent event = objectMapper.readValue(messageJson, WarehouseEvent.class);
            log.info("KafkaLiteListeners: Received WarehouseEvent: action={}, warehouseId={}", event.getAction(), event.getWarehouseId());

            evictCache("warehouses");
        } catch (Exception e) {
            log.error("KafkaLiteListeners: Failed to process WarehouseEvent message", e);
        }
    }

    private void evictCache(String cacheName) {
        var cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
            log.info("KafkaLiteListeners: Evicted '{}' cache.", cacheName);
        }
    }
}

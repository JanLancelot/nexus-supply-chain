package com.nexus.supplychain.service;

import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.ProductEvent;
import com.nexus.supplychain.kafkalite.event.WarehouseEvent;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CacheEvictionListenerTest {
    private final Map<String, Consumer<String>> handlers = new HashMap<>();
    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("products", "analytics", "warehouses");
    private final JsonMapper mapper = JsonMapper.builder().build();

    private void register() {
        KafkaLiteBroker broker = new KafkaLiteBroker() {
            @Override public void send(String topic, Object payload) { }
            @Override public void subscribe(String topic, Consumer<String> handler) { handlers.put(topic, handler); }
        };
        new CacheEvictionListener(broker, cacheManager, mapper).registerListeners();
    }

    @Test
    void stockAdjustmentsInvalidateInventoryAndAnalyticsWithoutRedisTtl() {
        register();
        cacheManager.getCache("products").put("all", "old stock");
        cacheManager.getCache("analytics").put("dashboard", "old totals");

        handlers.get("product-events").accept(mapper.writeValueAsString(new ProductEvent(UUID.randomUUID(), "STOCK_ADJUSTED")));

        assertNull(cacheManager.getCache("products").get("all"));
        assertNull(cacheManager.getCache("analytics").get("dashboard"));
    }

    @Test
    void warehouseChangesAlsoInvalidateDenormalizedProductNames() {
        register();
        cacheManager.getCache("warehouses").put("all", "old name");
        cacheManager.getCache("products").put("all", "old warehouse name");
        cacheManager.getCache("analytics").put("dashboard", "old warehouse name");

        handlers.get("warehouse-events").accept(mapper.writeValueAsString(new WarehouseEvent(UUID.randomUUID(), "UPDATED")));

        assertNull(cacheManager.getCache("warehouses").get("all"));
        assertNull(cacheManager.getCache("products").get("all"));
        assertNull(cacheManager.getCache("analytics").get("dashboard"));
    }
}

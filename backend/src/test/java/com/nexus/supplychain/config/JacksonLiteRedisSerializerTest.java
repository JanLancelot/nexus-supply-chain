package com.nexus.supplychain.config;

import com.nexus.supplychain.dto.AnalyticsDashboardResponse;
import com.nexus.supplychain.dto.PagedResponse;
import com.nexus.supplychain.dto.ProductResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JacksonLiteRedisSerializerTest {
    private final JacksonLiteRedisSerializer serializer = new JacksonLiteRedisSerializer(JsonMapper.builder().build());

    @Test
    void cachedProductPagesRetainDtoAndNumericTypes() {
        ProductResponse product = ProductResponse.builder().id(UUID.randomUUID()).sku("CACHE-1")
                .unitPrice(new BigDecimal("12.50")).stockQuantity(4).build();
        PagedResponse<ProductResponse> page = PagedResponse.<ProductResponse>builder()
                .content(new ArrayList<>(List.of(product))).pageSize(50).totalElements(-1).build();

        Object restored = serializer.deserialize(serializer.serialize(page));

        assertInstanceOf(PagedResponse.class, restored);
        assertEquals(page, restored);
        assertInstanceOf(ProductResponse.class, ((PagedResponse<?>) restored).getContent().get(0));
    }

    @Test
    void analyticsRoundTripIncludesMapsAndNestedProductDtos() {
        HashMap<String, Long> counts = new HashMap<>();
        counts.put("DRAFT", 3L);
        AnalyticsDashboardResponse dashboard = AnalyticsDashboardResponse.builder()
                .totalRevenue(BigDecimal.TEN).totalInventoryValue(new BigDecimal("30.50"))
                .orderStatusCounts(counts).warehouseStockCounts(new ArrayList<>(List.of(AnalyticsDashboardResponse.WarehouseStock.builder()
                        .warehouseId(UUID.randomUUID()).warehouseName("Regional").warehouseLocation("East").totalStock(3).build())))
                .topProducts(new ArrayList<>(List.of(AnalyticsDashboardResponse.TopProduct.builder()
                        .name("Product").totalQuantityOrdered(5).build()))).build();

        assertEquals(dashboard, serializer.deserialize(serializer.serialize(dashboard)));
    }

    @Test
    void rejectsClasspathTypesOutsideCacheAllowlist() {
        byte[] payload = "[\"java.io.File\",\"/tmp/cache-payload\"]".getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializationException.class, () -> serializer.deserialize(payload));
    }

    @Test
    void rejectsUnexpectedApplicationEntities() {
        byte[] payload = "{\"@class\":\"com.nexus.supplychain.model.User\",\"email\":\"injected@example.com\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(SerializationException.class, () -> serializer.deserialize(payload));
    }
}

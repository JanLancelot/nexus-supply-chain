package com.nexus.supplychain.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyticsDashboardResponse {
    private BigDecimal totalRevenue;
    private Map<String, Long> orderStatusCounts;
    private long lowStockCount;
    private BigDecimal totalInventoryValue;
    private List<WarehouseStock> warehouseStockCounts;
    private List<TopProduct> topProducts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WarehouseStock {
        private UUID warehouseId;
        private String warehouseName;
        private String warehouseLocation;
        private long totalStock;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TopProduct {
        private String name;
        private long totalQuantityOrdered;
    }
}

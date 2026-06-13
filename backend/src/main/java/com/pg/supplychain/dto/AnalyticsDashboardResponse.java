package com.pg.supplychain.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyticsDashboardResponse {
    private BigDecimal totalRevenue;
    private Map<String, Long> orderStatusCounts;
    private long lowStockCount;
    private BigDecimal totalInventoryValue;
    private Map<String, Long> warehouseStockCounts;
    private List<TopProduct> topProducts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TopProduct {
        private String name;
        private long totalQuantityOrdered;
    }
}

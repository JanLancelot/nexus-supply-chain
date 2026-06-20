package com.pg.supplychain.service;

import com.pg.supplychain.dto.AnalyticsDashboardResponse;
import com.pg.supplychain.repository.OrderRepository;
import com.pg.supplychain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    @Cacheable(value = "analytics", key = "'dashboard'", sync = true)
    public AnalyticsDashboardResponse getDashboardAnalytics() {
        log.info("AnalyticsService: Computing dashboard analytics metrics (Cache Miss)");

        // 1. Total Revenue
        BigDecimal totalRevenue = orderRepository.calculateTotalRevenue();

        // 2. Order Status Counts
        List<Object[]> rawOrderCounts = orderRepository.countOrdersByStatus();
        Map<String, Long> orderStatusCounts = new HashMap<>();
        for (Object[] row : rawOrderCounts) {
            if (row[0] != null && row[1] != null) {
                String status = row[0].toString();
                Long count = (Long) row[1];
                orderStatusCounts.put(status, count);
            }
        }

        // 3. Low Stock Count
        long lowStockCount = productRepository.countLowStockProducts();

        // 4. Total Inventory Value
        BigDecimal totalInventoryValue = productRepository.calculateTotalInventoryValue();

        // 5. Warehouse Stock Counts
        List<Object[]> rawWarehouseCounts = productRepository.countStockByWarehouse();
        Map<String, Long> warehouseStockCounts = new HashMap<>();
        for (Object[] row : rawWarehouseCounts) {
            if (row[0] != null && row[1] != null) {
                String warehouseName = row[0].toString();
                // SUM returns Long or BigDecimal/Double. In postgres SUM of integer stock_quantity returns bigint -> maps to Long.
                Long count = ((Number) row[1]).longValue();
                warehouseStockCounts.put(warehouseName, count);
            }
        }

        // 6. Top Products
        List<Object[]> rawTopProducts = orderRepository.findTop5OrderedProducts();
        List<AnalyticsDashboardResponse.TopProduct> topProducts = rawTopProducts.stream()
                .map(row -> AnalyticsDashboardResponse.TopProduct.builder()
                        .name(row[0].toString())
                        .totalQuantityOrdered(((Number) row[1]).longValue())
                        .build())
                .collect(Collectors.toList());

        return AnalyticsDashboardResponse.builder()
                .totalRevenue(totalRevenue)
                .orderStatusCounts(orderStatusCounts)
                .lowStockCount(lowStockCount)
                .totalInventoryValue(totalInventoryValue)
                .warehouseStockCounts(warehouseStockCounts)
                .topProducts(topProducts)
                .build();
    }
}

package com.nexus.supplychain.service;

import com.nexus.supplychain.model.OrderStatus;
import com.nexus.supplychain.repository.OrderRepository;
import com.nexus.supplychain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MonitoringSnapshotReader {
    private final ProductRepository products;
    private final OrderRepository orders;

    public record Snapshot(long products, long lowStockProducts, long units, double inventoryValue,
                           Map<OrderStatus, Long> orders) {}

    // Aggregate queries avoid loading entities or exposing product, supplier, and customer labels.
    // The transaction timeout bounds query execution; connection acquisition has Hikari's timeout.
    @Transactional(readOnly = true, timeout = 10)
    public Snapshot read() {
        long productCount = products.count();
        long lowStock = products.countLowStockProducts();
        long units = products.countActiveInventoryUnits();
        double value = products.calculateTotalInventoryValue().doubleValue();
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : orders.countOrdersByStatus()) {
            counts.put((OrderStatus) row[0], ((Number) row[1]).longValue());
        }
        return new Snapshot(productCount, lowStock, units, value, Map.copyOf(counts));
    }
}

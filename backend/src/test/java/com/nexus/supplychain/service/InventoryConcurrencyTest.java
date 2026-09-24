package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.OrderStatusUpdateRequest;
import com.nexus.supplychain.dto.ProductAdjustRequest;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.model.*;
import com.nexus.supplychain.repository.*;
import com.nexus.supplychain.security.SecurityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@DataJpaTest(showSql = false)
@Import({ProductService.class, OrderService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryConcurrencyTest {
    @Autowired private ProductService productService;
    @Autowired private OrderService orderService;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private WarehouseRepository warehouseRepository;
    @MockitoBean private AuditService auditService;
    @MockitoBean private KafkaLiteBroker broker;
    @MockitoBean private SecurityContextService securityContextService;

    @BeforeEach
    void authenticateAdmin() {
        when(securityContextService.getCurrentUser()).thenReturn(
                User.builder().role(Role.builder().name("ROLE_ADMIN").build()).build());
    }

    @Test
    void concurrentAdjustmentsDoNotLoseStock() throws Exception {
        Product product = newProduct();

        concurrently(4, worker -> {
            for (int i = 0; i < 10; i++) {
                productService.adjustProductStock(product.getId(), new ProductAdjustRequest(1, "CYCLIC_COUNT_DISCREPANCY"));
            }
        });

        assertEquals(40, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
    }

    @Test
    void repeatedConcurrentDeliveryReceivesAnOrderOnlyOnce() throws Exception {
        Product product = newProduct();
        Order order = newShippedOrder(product);

        concurrently(4, worker -> orderService.updateOrderStatus(order.getId(), new OrderStatusUpdateRequest("DELIVERED")));

        assertEquals(5, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
        assertEquals(OrderStatus.DELIVERED, orderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void differentConcurrentDeliveriesAccumulateStock() throws Exception {
        Product product = newProduct();
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            orders.add(newShippedOrder(product));
        }

        concurrently(4, worker -> orderService.updateOrderStatus(orders.get(worker).getId(), new OrderStatusUpdateRequest("DELIVERED")));

        assertEquals(20, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
    }

    private Product newProduct() {
        Warehouse warehouse = warehouseRepository.save(Warehouse.builder().name(UUID.randomUUID().toString()).build());
        return productRepository.save(Product.builder().warehouse(warehouse).sku(UUID.randomUUID().toString()).name("Concurrency fixture")
                .unitPrice(BigDecimal.ONE).build());
    }

    private Order newShippedOrder(Product product) {
        Supplier supplier = supplierRepository.save(Supplier.builder().name(UUID.randomUUID().toString()).build());
        Warehouse warehouse = product.getWarehouse();
        Order order = Order.builder().orderNumber(UUID.randomUUID().toString()).supplier(supplier).warehouse(warehouse)
                .status(OrderStatus.SHIPPED).totalAmount(BigDecimal.valueOf(5)).build();
        order.getItems().add(OrderItem.builder().order(order).product(product).quantity(5)
                .unitPrice(BigDecimal.ONE).subtotal(BigDecimal.valueOf(5)).build());
        return orderRepository.save(order);
    }

    private void concurrently(int workers, IntConsumer action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int worker = i;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Workers did not start together");
                    }
                    action.accept(worker);
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(15, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}

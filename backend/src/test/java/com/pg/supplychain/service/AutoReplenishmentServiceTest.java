package com.pg.supplychain.service;

import com.pg.supplychain.dto.OrderResponse;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.ProductEvent;
import com.pg.supplychain.model.Product;
import com.pg.supplychain.model.Supplier;
import com.pg.supplychain.model.Warehouse;
import com.pg.supplychain.repository.OrderRepository;
import com.pg.supplychain.repository.ProductRepository;
import com.pg.supplychain.repository.SupplierRepository;
import com.pg.supplychain.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutoReplenishmentServiceTest {

    @Mock private KafkaLiteBroker broker;
    @Mock private OrderService orderService;
    @Mock private ProductRepository productRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private AutoReplenishmentService autoReplenishmentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        autoReplenishmentService.init();
    }

    @Test
    void testHandleProductEvent_LowStockTrigger() throws Exception {
        UUID productId = UUID.randomUUID();
        ProductEvent event = new ProductEvent(productId, "STOCK_ADJUSTED");
        String json = "{}";

        when(objectMapper.readValue(json, ProductEvent.class)).thenReturn(event);

        Product product = Product.builder()
                .id(productId)
                .sku("SKU-LOW")
                .stockQuantity(1)
                .reorderLevel(5)
                .isActive(true)
                .warehouse(Warehouse.builder().id(UUID.randomUUID()).name("Main WH").build())
                .build();

        // isLowStockIndicator returns stockQuantity < reorderLevel
        assertTrue(product.isLowStockIndicator());

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(orderRepository.countOpenOrdersForProduct(productId)).thenReturn(0L);

        Supplier supplier = Supplier.builder().id(UUID.randomUUID()).name("Preferred Supplier").build();
        when(supplierRepository.findPreferredSupplierForProduct(productId)).thenReturn(Optional.of(supplier));

        OrderResponse orderResponse = OrderResponse.builder().orderNumber("ORD-SYS-101").status("DRAFT").build();
        when(orderService.createSystemOrder(any(), any(), any())).thenReturn(orderResponse);

        autoReplenishmentService.handleProductEvent(json);

        verify(orderService, times(1)).createSystemOrder(eq(supplier.getId()), eq(product.getWarehouse().getId()), anyList());
    }

    @Test
    void testHandleProductEvent_CooldownBlocks() throws Exception {
        UUID productId = UUID.randomUUID();
        ProductEvent event = new ProductEvent(productId, "STOCK_ADJUSTED");
        String json = "{}";

        when(objectMapper.readValue(json, ProductEvent.class)).thenReturn(event);

        Product product = Product.builder()
                .id(productId)
                .sku("SKU-LOW")
                .stockQuantity(1)
                .reorderLevel(5)
                .isActive(true)
                .warehouse(Warehouse.builder().id(UUID.randomUUID()).name("WH-1").build())
                .build();

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(orderRepository.countOpenOrdersForProduct(productId)).thenReturn(0L);
        Supplier supplier = Supplier.builder().id(UUID.randomUUID()).build();
        when(supplierRepository.findPreferredSupplierForProduct(productId)).thenReturn(Optional.of(supplier));
        when(orderService.createSystemOrder(any(), any(), any())).thenReturn(OrderResponse.builder().build());

        // First event
        autoReplenishmentService.handleProductEvent(json);
        // Second event immediately after
        autoReplenishmentService.handleProductEvent(json);

        // Verify only 1 system order is created due to cooldown
        verify(orderService, times(1)).createSystemOrder(any(), any(), any());
    }
}

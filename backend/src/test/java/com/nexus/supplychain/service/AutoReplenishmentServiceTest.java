package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.OrderResponse;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.ProductEvent;
import com.nexus.supplychain.model.Product;
import com.nexus.supplychain.model.Supplier;
import com.nexus.supplychain.model.Warehouse;
import com.nexus.supplychain.repository.OrderRepository;
import com.nexus.supplychain.repository.ProductRepository;
import com.nexus.supplychain.repository.SupplierRepository;
import com.nexus.supplychain.repository.WarehouseRepository;
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

        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(orderRepository.countOpenOrdersForProduct(productId)).thenReturn(0L);

        Supplier supplier = Supplier.builder().isActive(true).id(UUID.randomUUID()).name("Preferred Supplier").build();
        when(supplierRepository.findPreferredSupplierForProduct(productId)).thenReturn(Optional.of(supplier));

        OrderResponse orderResponse = OrderResponse.builder().orderNumber("ORD-SYS-101").status("DRAFT").build();
        when(orderService.createSystemOrder(any(), any(), any())).thenReturn(orderResponse);

        autoReplenishmentService.handleProductEvent(json);

        verify(orderService, times(1)).createSystemOrder(eq(supplier.getId()), eq(product.getWarehouse().getId()), anyList());
    }

    @Test
    void testHandleProductEvent_ExistingOrderBlocksDuplicate() throws Exception {
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

        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(product));
        when(orderRepository.countOpenOrdersForProduct(productId)).thenReturn(0L, 1L);
        Supplier supplier = Supplier.builder().isActive(true).id(UUID.randomUUID()).build();
        when(supplierRepository.findPreferredSupplierForProduct(productId)).thenReturn(Optional.of(supplier));
        when(orderService.createSystemOrder(any(), any(), any())).thenReturn(OrderResponse.builder().build());

        // First event
        autoReplenishmentService.handleProductEvent(json);
        // Second event immediately after
        autoReplenishmentService.handleProductEvent(json);

        // The database lookup, rather than an instance-local timer, prevents another order.
        verify(orderService, times(1)).createSystemOrder(any(), any(), any());
    }
    @Test
    void missingSupplierAssociationDoesNotChooseAnArbitrarySupplier() {
        UUID productId = UUID.randomUUID();
        when(objectMapper.readValue("{}", ProductEvent.class)).thenReturn(new ProductEvent(productId, "STOCK_ADJUSTED"));
        when(productRepository.findByIdForUpdate(productId)).thenReturn(Optional.of(Product.builder()
                .id(productId).sku("UNSOURCED").stockQuantity(0).reorderLevel(5).isActive(true).build()));
        when(supplierRepository.findPreferredSupplierForProduct(productId)).thenReturn(Optional.empty());
        autoReplenishmentService.handleProductEvent("{}");
        verify(orderService, never()).createSystemOrder(any(), any(), any());
        verify(supplierRepository, never()).findAll();
    }

    @Test
    void transientDatabaseFailurePropagatesForBrokerRetry() {
        UUID productId = UUID.randomUUID();
        when(objectMapper.readValue("{}", ProductEvent.class)).thenReturn(new ProductEvent(productId, "STOCK_ADJUSTED"));
        when(productRepository.findByIdForUpdate(productId)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> autoReplenishmentService.handleProductEvent("{}"));
    }
}

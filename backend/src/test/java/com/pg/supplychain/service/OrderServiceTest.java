package com.pg.supplychain.service;

import com.pg.supplychain.dto.OrderCreateRequest;
import com.pg.supplychain.dto.OrderItemRequest;
import com.pg.supplychain.dto.OrderStatusUpdateRequest;
import com.pg.supplychain.dto.OrderResponse;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.model.*;
import com.pg.supplychain.repository.*;
import com.pg.supplychain.security.SecurityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private ProductRepository productRepository;
    @Mock private SecurityContextService securityContextService;
    @Mock private SupplierRepository supplierRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private AuditService auditService;
    @Mock private KafkaLiteBroker kafkaLiteBroker;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateOrder_Unauthenticated() {
        when(securityContextService.getCurrentUser()).thenReturn(null);
        OrderCreateRequest request = OrderCreateRequest.builder().build();

        assertThrows(AccessDeniedException.class, () -> orderService.createOrder(request));
    }

    @Test
    void testCreateOrder_Success() {
        User user = User.builder().id(UUID.randomUUID()).email("staff@pg.com").build();
        Supplier supplier = Supplier.builder().id(UUID.randomUUID()).name("Sup").build();
        Warehouse warehouse = Warehouse.builder().id(UUID.randomUUID()).name("Wh").build();
        Product product = Product.builder().id(UUID.randomUUID()).sku("SKU-1").unitPrice(BigDecimal.TEN).build();

        when(securityContextService.getCurrentUser()).thenReturn(user);
        when(supplierRepository.findById(any())).thenReturn(Optional.of(supplier));
        when(warehouseRepository.findById(any())).thenReturn(Optional.of(warehouse));
        when(productRepository.findById(any())).thenReturn(Optional.of(product));
        when(orderRepository.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        OrderItemRequest itemReq = new OrderItemRequest(product.getId(), 5);
        OrderCreateRequest request = OrderCreateRequest.builder()
                .supplierId(supplier.getId())
                .warehouseId(warehouse.getId())
                .items(Collections.singletonList(itemReq))
                .build();

        OrderResponse response = orderService.createOrder(request);
        assertNotNull(response);
        assertEquals("DRAFT", response.getStatus());
        assertEquals(0, response.getTotalAmount().compareTo(BigDecimal.valueOf(50)));
        verify(kafkaLiteBroker, times(1)).send(eq("order-events"), any());
    }

    @Test
    void testUpdateOrderStatus_InvalidTransition() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).status(OrderStatus.DRAFT).items(new ArrayList<>()).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("SHIPPED");

        assertThrows(BadRequestException.class, () -> orderService.updateOrderStatus(orderId, request));
    }

    @Test
    void testUpdateOrderStatus_StaffApprovedDenied() {
        UUID orderId = UUID.randomUUID();
        Role staffRole = Role.builder().name("ROLE_STAFF").build();
        User staff = User.builder().role(staffRole).build();

        Order order = Order.builder().id(orderId).status(OrderStatus.PENDING_APPROVAL).items(new ArrayList<>()).build();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(securityContextService.getCurrentUser()).thenReturn(staff);

        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("APPROVED");

        assertThrows(AccessDeniedException.class, () -> orderService.updateOrderStatus(orderId, request));
    }

    @Test
    void testUpdateOrderStatus_DeliveredStockIncrement() {
        UUID orderId = UUID.randomUUID();
        Role adminRole = Role.builder().name("ROLE_ADMIN").build();
        User admin = User.builder().role(adminRole).build();

        Product product = Product.builder().id(UUID.randomUUID()).stockQuantity(10).build();
        OrderItem item = OrderItem.builder().product(product).quantity(5).build();
        Order order = Order.builder()
                .id(orderId)
                .status(OrderStatus.SHIPPED)
                .items(Collections.singletonList(item))
                .supplier(Supplier.builder().id(UUID.randomUUID()).build())
                .warehouse(Warehouse.builder().id(UUID.randomUUID()).build())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(securityContextService.getCurrentUser()).thenReturn(admin);
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("DELIVERED");
        OrderResponse response = orderService.updateOrderStatus(orderId, request);

        assertNotNull(response);
        assertEquals("DELIVERED", response.getStatus());
        assertEquals(15, product.getStockQuantity());
        verify(productRepository, times(1)).save(product);
    }
}

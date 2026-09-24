package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.OrderCreateRequest;
import com.nexus.supplychain.dto.OrderItemRequest;
import com.nexus.supplychain.dto.OrderStatusUpdateRequest;
import com.nexus.supplychain.dto.OrderResponse;
import com.nexus.supplychain.exception.BadRequestException;
import com.nexus.supplychain.exception.ResourceNotFoundException;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.model.*;
import com.nexus.supplychain.repository.*;
import com.nexus.supplychain.security.SecurityContextService;
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
        User user = User.builder().id(UUID.randomUUID()).email("staff@example.test").build();
        Supplier supplier = Supplier.builder().id(UUID.randomUUID()).name("Sup").build();
        Warehouse warehouse = Warehouse.builder().id(UUID.randomUUID()).name("Wh").build();
        Product product = Product.builder().id(UUID.randomUUID()).sku("SKU-1").name("Order product").warehouse(warehouse).unitPrice(BigDecimal.TEN).build();

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
        assertEquals("Order product", response.getItems().get(0).getProductName());
        assertEquals("SKU-1", response.getItems().get(0).getProductSku());
        assertEquals(0, response.getTotalAmount().compareTo(BigDecimal.valueOf(50)));
        verify(kafkaLiteBroker, times(1)).send(eq("order-events"), any());
    }

    @Test
    void testUpdateOrderStatus_InvalidTransition() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder().id(orderId).status(OrderStatus.DRAFT).items(new ArrayList<>()).build();
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        when(securityContextService.getCurrentUser()).thenReturn(User.builder().role(Role.builder().name("ROLE_ADMIN").build()).build());
        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("SHIPPED");

        assertThrows(BadRequestException.class, () -> orderService.updateOrderStatus(orderId, request));
    }

    @Test
    void testUpdateOrderStatus_StaffApprovedDenied() {
        UUID orderId = UUID.randomUUID();
        Role staffRole = Role.builder().name("ROLE_STAFF").build();
        User staff = User.builder().role(staffRole).build();

        Order order = Order.builder().id(orderId).status(OrderStatus.PENDING_APPROVAL).items(new ArrayList<>()).build();
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(securityContextService.getCurrentUser()).thenReturn(staff);

        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("APPROVED");

        assertThrows(AccessDeniedException.class, () -> orderService.updateOrderStatus(orderId, request));
    }

    @Test
    void testUpdateOrderStatus_DeliveredStockIncrement() {
        UUID orderId = UUID.randomUUID();
        Role adminRole = Role.builder().name("ROLE_ADMIN").build();
        User admin = User.builder().role(adminRole).build();

        Warehouse warehouse = Warehouse.builder().id(UUID.randomUUID()).build();
        Product product = Product.builder().id(UUID.randomUUID()).warehouse(warehouse).stockQuantity(10).build();
        OrderItem item = OrderItem.builder().product(product).quantity(5).build();
        Order order = Order.builder()
                .id(orderId)
                .status(OrderStatus.SHIPPED)
                .items(Collections.singletonList(item))
                .supplier(Supplier.builder().id(UUID.randomUUID()).build())
                .warehouse(warehouse)
                .build();

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(securityContextService.getCurrentUser()).thenReturn(admin);
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));

        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("DELIVERED");
        OrderResponse response = orderService.updateOrderStatus(orderId, request);

        assertNotNull(response);
        assertEquals("DELIVERED", response.getStatus());
        assertEquals(15, product.getStockQuantity());
        verify(productRepository, times(1)).save(product);
        verify(auditService).logChange(eq("Product"), eq(product.getId()), eq("ACTION_ORDER_RECEIPT"),
                eq(java.util.Map.of("stockQuantity", 10)), argThat(value -> {
                    var state = (java.util.Map<?, ?>) value;
                    return orderId.equals(state.get("orderId")) && Integer.valueOf(15).equals(state.get("stockQuantity"));
                }));
    }

    @Test
    void testCreateSystemOrder_RejectsNegativeQuantityBeforeDatabaseWork() {
        assertThrows(BadRequestException.class, () -> orderService.createSystemOrder(
                UUID.randomUUID(), UUID.randomUUID(),
                Collections.singletonList(new OrderItemRequest(UUID.randomUUID(), -1))));
        verifyNoInteractions(supplierRepository, warehouseRepository, productRepository, orderRepository);
    }

    @Test
    void testCreateOrder_RejectsDuplicateProducts() {
        when(securityContextService.getCurrentUser()).thenReturn(User.builder().build());
        UUID productId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(UUID.randomUUID(), UUID.randomUUID(),
                java.util.List.of(new OrderItemRequest(productId, 1), new OrderItemRequest(productId, 2)));

        assertThrows(BadRequestException.class, () -> orderService.createOrder(request));
        verifyNoInteractions(supplierRepository, warehouseRepository, productRepository, orderRepository);
    }

    @Test
    void testUpdateOrderStatus_IdempotentRequestStillRequiresAuthentication() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(
                Order.builder().id(orderId).status(OrderStatus.DELIVERED).build()));

        assertThrows(AccessDeniedException.class, () -> orderService.updateOrderStatus(orderId,
                new OrderStatusUpdateRequest("DELIVERED")));
        verify(orderRepository, never()).save(any());
    }

    @Test
    void testUpdateOrderStatus_RejectsDeliveryOverflow() {
        UUID orderId = UUID.randomUUID();
        Warehouse warehouse = Warehouse.builder().id(UUID.randomUUID()).build();
        Product product = Product.builder().id(UUID.randomUUID()).warehouse(warehouse).stockQuantity(Integer.MAX_VALUE).build();
        Order order = Order.builder().id(orderId).warehouse(warehouse).status(OrderStatus.SHIPPED)
                .items(Collections.singletonList(OrderItem.builder().product(product).quantity(1).build())).build();
        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(productRepository.findByIdForUpdate(product.getId())).thenReturn(Optional.of(product));
        when(securityContextService.getCurrentUser()).thenReturn(
                User.builder().role(Role.builder().name("ROLE_ADMIN").build()).build());

        assertThrows(BadRequestException.class, () -> orderService.updateOrderStatus(orderId,
                new OrderStatusUpdateRequest("DELIVERED")));
        assertEquals(Integer.MAX_VALUE, product.getStockQuantity());
        verify(productRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(auditService, kafkaLiteBroker);
    }

    @Test
    void testStaffCannotCancelApprovedOrShippedOrders() {
        when(securityContextService.getCurrentUser()).thenReturn(
                User.builder().role(Role.builder().name("ROLE_STAFF").build()).build());
        for (OrderStatus status : java.util.List.of(OrderStatus.APPROVED, OrderStatus.SHIPPED)) {
            UUID orderId = UUID.randomUUID();
            when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(
                    Order.builder().id(orderId).status(status).build()));

            assertThrows(AccessDeniedException.class, () -> orderService.updateOrderStatus(orderId,
                    new OrderStatusUpdateRequest("CANCELLED")));
        }
        verify(orderRepository, never()).save(any());
        verifyNoInteractions(auditService, kafkaLiteBroker);
    }
}

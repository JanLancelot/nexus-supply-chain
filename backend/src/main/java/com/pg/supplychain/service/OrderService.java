package com.pg.supplychain.service;

import com.pg.supplychain.dto.*;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.OrderEvent;
import com.pg.supplychain.model.*;
import com.pg.supplychain.repository.*;
import com.pg.supplychain.security.SecurityContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final SecurityContextService securityContextService;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final AuditService auditService;
    private final KafkaLiteBroker kafkaLiteBroker;
    private final AtomicLong orderCounter = new AtomicLong(System.currentTimeMillis());

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request) {
        User creator = securityContextService.getCurrentUser();
        if (creator == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with ID: " + request.getSupplierId()));

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + request.getWarehouseId()));

        String orderNum = "ORD-" + orderCounter.incrementAndGet();

        Order order = Order.builder()
                .orderNumber(orderNum)
                .supplier(supplier)
                .warehouse(warehouse)
                .status(OrderStatus.DRAFT)
                .createdBy(creator)
                .totalAmount(BigDecimal.ZERO)
                .build();

        List<OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemReq : request.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + itemReq.getProductId()));

            BigDecimal unitPrice = product.getUnitPrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            total = total.add(subtotal);

            items.add(OrderItem.builder()
                    .order(order)
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build());
        }

        order.setItems(items);
        order.setTotalAmount(total);

        Order savedOrder = orderRepository.save(order);

        // Audit log order creation
        auditService.logChange(
                "Order",
                savedOrder.getId(),
                "ACTION_CREATE_ORDER",
                null,
                mapToAuditState(savedOrder)
        );

        // Publish event to kafka-lite
        kafkaLiteBroker.send("order-events", new OrderEvent(savedOrder.getId(), savedOrder.getStatus().name()));

        return mapToResponse(savedOrder);
    }

    @Transactional
    public OrderResponse createSystemOrder(UUID supplierId, UUID warehouseId, List<OrderItemRequest> itemRequests) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with ID: " + supplierId));

        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + warehouseId));

        String orderNum = "ORD-SYS-" + orderCounter.incrementAndGet();

        Order order = Order.builder()
                .orderNumber(orderNum)
                .supplier(supplier)
                .warehouse(warehouse)
                .status(OrderStatus.DRAFT)
                .expectedDeliveryDate(OffsetDateTime.now().plusDays(supplier.getLeadTimeDays()))
                .totalAmount(BigDecimal.ZERO)
                .build();

        List<OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemReq : itemRequests) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + itemReq.getProductId()));

            BigDecimal unitPrice = product.getUnitPrice();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            total = total.add(subtotal);

            items.add(OrderItem.builder()
                    .order(order)
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build());
        }

        order.setItems(items);
        order.setTotalAmount(total);

        Order savedOrder = orderRepository.save(order);

        // Audit log order creation
        auditService.logChange(
                "Order",
                savedOrder.getId(),
                "ACTION_CREATE_SYSTEM_ORDER",
                null,
                mapToAuditState(savedOrder)
        );

        // Publish event to kafka-lite
        kafkaLiteBroker.send("order-events", new OrderEvent(savedOrder.getId(), savedOrder.getStatus().name()));

        return mapToResponse(savedOrder);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse updateOrderStatus(UUID orderId, OrderStatusUpdateRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + orderId));

        OrderStatus currentStatus = order.getStatus();

        OrderStatus targetStatus;
        try {
            targetStatus = OrderStatus.valueOf(request.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid status: " + request.getStatus());
        }

        if (currentStatus == targetStatus) {
            return mapToResponse(order);
        }

        // Validate FSM Transitions
        boolean validTransition = false;
        if (currentStatus == OrderStatus.DRAFT) {
            validTransition = (targetStatus == OrderStatus.PENDING_APPROVAL || targetStatus == OrderStatus.CANCELLED);
        } else if (currentStatus == OrderStatus.PENDING_APPROVAL) {
            validTransition = (targetStatus == OrderStatus.APPROVED || targetStatus == OrderStatus.CANCELLED);
        } else if (currentStatus == OrderStatus.APPROVED) {
            validTransition = (targetStatus == OrderStatus.SHIPPED || targetStatus == OrderStatus.CANCELLED);
        } else if (currentStatus == OrderStatus.SHIPPED) {
            validTransition = (targetStatus == OrderStatus.DELIVERED || targetStatus == OrderStatus.CANCELLED);
        }

        if (!validTransition) {
            throw new BadRequestException("State machine validation error. Cannot advance " + currentStatus + " orders to " + targetStatus + " status.");
        }

        // Validate Role Privileges
        User currentUser = securityContextService.getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        if (currentUser.getRole().getName().equals("ROLE_STAFF")) {
            if (targetStatus == OrderStatus.APPROVED || targetStatus == OrderStatus.SHIPPED || targetStatus == OrderStatus.DELIVERED) {
                throw new AccessDeniedException("Access denied: Staff cannot advance order to approved/shipped/delivered status");
            }
        }

        // Perform transactional increments when order moves to DELIVERED
        if (targetStatus == OrderStatus.DELIVERED) {
            order.setActualDeliveryDate(OffsetDateTime.now());
            for (OrderItem item : order.getItems()) {
                Product product = item.getProduct();
                int oldStock = product.getStockQuantity();
                int newStock = oldStock + item.getQuantity();

                // Save old/new state mapping for audit
                Map<String, Object> oldProductState = new HashMap<>();
                oldProductState.put("stockQuantity", oldStock);

                product.setStockQuantity(newStock);
                productRepository.save(product);

                Map<String, Object> newProductState = new HashMap<>();
                newProductState.put("stockQuantity", newStock);

                // Audit the product stock increment
                auditService.logChange(
                        "Product",
                        product.getId(),
                        "ACTION_MANUAL_ADJUSTMENT",
                        oldProductState,
                        newProductState
                );
            }
        }

        // Apply state transition
        Map<String, Object> oldOrderState = new HashMap<>();
        oldOrderState.put("status", currentStatus.name());

        order.setStatus(targetStatus);
        Order updatedOrder = orderRepository.save(order);

        Map<String, Object> newOrderState = new HashMap<>();
        newOrderState.put("status", targetStatus.name());

        // Audit order status update
        auditService.logChange(
                "Order",
                orderId,
                "ACTION_UPDATE_ORDER_STATUS",
                oldOrderState,
                newOrderState
        );

        // Publish event to kafka-lite
        kafkaLiteBroker.send("order-events", new OrderEvent(updatedOrder.getId(), updatedOrder.getStatus().name()));

        return mapToResponse(updatedOrder);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders(org.springframework.data.domain.Pageable pageable) {
        return orderRepository.findAllWithDetails(pageable).getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + orderId));
        return mapToResponse(order);
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream().map(item ->
                OrderItemResponse.builder()
                        .productId(item.getProduct().getId())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .subtotal(item.getSubtotal())
                        .build()
        ).collect(Collectors.toList());

        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .supplierId(order.getSupplier().getId())
                .supplierName(order.getSupplier().getName())
                .warehouseId(order.getWarehouse().getId())
                .warehouseName(order.getWarehouse().getName())
                .status(order.getStatus().name())
                .totalAmount(order.getTotalAmount())
                .createdBy(order.getCreatedBy() != null ? order.getCreatedBy().getId() : null)
                .items(items)
                .build();
    }

    private Map<String, Object> mapToAuditState(Order order) {
        Map<String, Object> state = new HashMap<>();
        state.put("orderNumber", order.getOrderNumber());
        state.put("status", order.getStatus().name());
        state.put("totalAmount", order.getTotalAmount());
        return state;
    }
}

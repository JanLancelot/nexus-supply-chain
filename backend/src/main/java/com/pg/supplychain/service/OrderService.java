package com.pg.supplychain.service;

import com.pg.supplychain.dto.*;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.model.*;
import com.pg.supplychain.repository.*;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final AuditService auditService;

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request) {
        User creator = getCurrentUser();
        if (creator == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with ID: " + request.getSupplierId()));

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + request.getWarehouseId()));

        String orderNum = "ORD-" + System.currentTimeMillis();

        Order order = Order.builder()
                .orderNumber(orderNum)
                .supplier(supplier)
                .warehouse(warehouse)
                .status("DRAFT")
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

        return mapToResponse(savedOrder);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse updateOrderStatus(UUID orderId, OrderStatusUpdateRequest request) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + orderId));

        OrderStatus currentStatus;
        try {
            currentStatus = OrderStatus.valueOf(order.getStatus().toUpperCase());
        } catch (IllegalArgumentException e) {
            currentStatus = OrderStatus.DRAFT;
        }

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
        User currentUser = getCurrentUser();
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

        order.setStatus(targetStatus.name());
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

        return mapToResponse(updatedOrder);
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Map) {
            Map<?, ?> principal = (Map<?, ?>) auth.getPrincipal();
            String userIdStr = (String) principal.get("userId");
            if (userIdStr != null) {
                return userRepository.findById(UUID.fromString(userIdStr)).orElse(null);
            }
        }
        return null;
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
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .createdBy(order.getCreatedBy() != null ? order.getCreatedBy().getId() : null)
                .items(items)
                .build();
    }

    private Map<String, Object> mapToAuditState(Order order) {
        Map<String, Object> state = new HashMap<>();
        state.put("orderNumber", order.getOrderNumber());
        state.put("status", order.getStatus());
        state.put("totalAmount", order.getTotalAmount());
        return state;
    }
}

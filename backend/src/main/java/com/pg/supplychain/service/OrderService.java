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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

    @Transactional
    public OrderResponse createOrder(OrderCreateRequest request) {
        User creator = securityContextService.getCurrentUser();
        if (creator == null) {
            throw new AccessDeniedException("User is not authenticated");
        }
        if (request == null) {
            throw new BadRequestException("Order details are required");
        }
        return createOrder(request.getSupplierId(), request.getWarehouseId(), request.getItems(), creator);
    }

    @Transactional
    public OrderResponse createSystemOrder(UUID supplierId, UUID warehouseId, List<OrderItemRequest> itemRequests) {
        return createOrder(supplierId, warehouseId, itemRequests, null);
    }

    private OrderResponse createOrder(UUID supplierId, UUID warehouseId, List<OrderItemRequest> itemRequests, User creator) {
        if (supplierId == null || warehouseId == null) {
            throw new BadRequestException("Supplier and warehouse IDs are required");
        }
        validateItems(itemRequests);
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with ID: " + supplierId));
        if (!supplier.isActive()) {
            throw new BadRequestException("Cannot order from an inactive supplier");
        }
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + warehouseId));
        boolean systemOrder = creator == null;
        Order order = Order.builder()
                .orderNumber((systemOrder ? "ORD-SYS-" : "ORD-") + UUID.randomUUID())
                .supplier(supplier)
                .warehouse(warehouse)
                .status(OrderStatus.DRAFT)
                .createdBy(creator)
                .build();
        if (systemOrder) {
            order.setExpectedDeliveryDate(OffsetDateTime.now().plusDays(supplier.getLeadTimeDays()));
        }

        List<OrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemReq : itemRequests) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + itemReq.getProductId()));
            if (!product.isActive()) {
                throw new BadRequestException("Cannot order an inactive product");
            }
            BigDecimal unitPrice = product.getUnitPrice();
            if (unitPrice == null || unitPrice.signum() < 0) {
                throw new BadRequestException("Product must have a non-negative unit price");
            }
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(itemReq.getQuantity()));
            total = total.add(subtotal);
            if (total.compareTo(new BigDecimal("9999999999.99")) > 0) {
                throw new BadRequestException("Order total exceeds the supported amount");
            }
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
        auditService.logChange("Order", savedOrder.getId(),
                systemOrder ? "ACTION_CREATE_SYSTEM_ORDER" : "ACTION_CREATE_ORDER", null, mapToAuditState(savedOrder));
        kafkaLiteBroker.send("order-events", new OrderEvent(savedOrder.getId(), savedOrder.getStatus().name()));
        return mapToResponse(savedOrder);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderResponse updateOrderStatus(UUID orderId, OrderStatusUpdateRequest request) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with ID: " + orderId));

        User currentUser = securityContextService.getCurrentUser();
        if (currentUser == null || currentUser.getRole() == null
                || !("ROLE_ADMIN".equals(currentUser.getRole().getName())
                || "ROLE_STAFF".equals(currentUser.getRole().getName()))) {
            throw new AccessDeniedException("User is not authorized to update orders");
        }

        OrderStatus currentStatus = order.getStatus();

        OrderStatus targetStatus;
        if (request == null || request.getStatus() == null || request.getStatus().isBlank()) {
            throw new BadRequestException("Status is required");
        }
        try {
            targetStatus = OrderStatus.valueOf(request.getStatus().toUpperCase(Locale.ROOT));
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

        if (currentUser.getRole().getName().equals("ROLE_STAFF")) {
            if ((currentStatus != OrderStatus.DRAFT && currentStatus != OrderStatus.PENDING_APPROVAL)
                    || (targetStatus != OrderStatus.PENDING_APPROVAL && targetStatus != OrderStatus.CANCELLED)) {
                throw new AccessDeniedException("Staff may submit drafts or cancel orders awaiting approval");
            }
        }

        // Perform transactional increments when order moves to DELIVERED
        if (targetStatus == OrderStatus.DELIVERED) {
            order.setActualDeliveryDate(OffsetDateTime.now());
            // Lock in product ID order so concurrent deliveries cannot acquire rows in opposite orders.
            List<OrderItem> sortedItems = order.getItems().stream()
                    .sorted(Comparator.comparing(item -> item.getProduct().getId()))
                    .toList();
            for (OrderItem item : sortedItems) {
                UUID productId = item.getProduct().getId();
                Product product = productRepository.findByIdForUpdate(productId)
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + productId));
                int oldStock = product.getStockQuantity();
                long deliveredStock = (long) oldStock + item.getQuantity();
                if (item.getQuantity() <= 0 || deliveredStock > Integer.MAX_VALUE) {
                    throw new BadRequestException("Delivery would put stock quantity outside the supported range");
                }
                int newStock = (int) deliveredStock;

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
    public PagedResponse<OrderResponse> getAllOrders(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Slice<Order> orderSlice = orderRepository.findSliceBy(pageRequest);
        List<OrderResponse> content = orderSlice.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return PagedResponse.<OrderResponse>builder()
                .content(content)
                .totalElements(-1L)
                .totalPages(-1)
                .pageNumber(orderSlice.getNumber())
                .pageSize(orderSlice.getSize())
                .hasNext(orderSlice.hasNext())
                .build();
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

    private void validateItems(List<OrderItemRequest> items) {
        if (items == null || items.isEmpty() || items.size() > 500) {
            throw new BadRequestException("An order must contain between 1 and 500 items");
        }
        HashSet<UUID> productIds = new HashSet<>();
        for (OrderItemRequest item : items) {
            if (item == null || item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BadRequestException("Each order item requires a product and a positive quantity");
            }
            if (!productIds.add(item.getProductId())) {
                throw new BadRequestException("A product may appear only once per order");
            }
        }
    }
}

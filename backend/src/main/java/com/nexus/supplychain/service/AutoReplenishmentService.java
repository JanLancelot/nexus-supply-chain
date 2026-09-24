package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.OrderItemRequest;
import com.nexus.supplychain.dto.OrderResponse;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.ProductEvent;
import com.nexus.supplychain.model.Product;
import com.nexus.supplychain.model.Supplier;
import com.nexus.supplychain.model.Warehouse;
import com.nexus.supplychain.repository.OrderRepository;
import com.nexus.supplychain.repository.ProductRepository;
import com.nexus.supplychain.repository.SupplierRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoReplenishmentService {

    private final KafkaLiteBroker broker;
    private final OrderService orderService;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    public void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        broker.subscribe("product-events", "replenishment", this::handleProductEvent);
        log.info("AutoReplenishmentService: Subscribed to product-events topic.");
    }

    public void handleProductEvent(String messageJson) {
        try {
            ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
            UUID productId = event.getProductId();
            log.info("AutoReplenishmentService: Received ProductEvent for product={}", productId);

            // Serialize the stock check and open-order lookup across all application instances.
            transactionTemplate.executeWithoutResult(status -> {
                Product product = productRepository.findByIdForUpdate(productId).orElse(null);
                if (product == null) {
                    log.warn("AutoReplenishmentService: Product not found: {}", productId);
                    return;
                }

                if (!product.isActive()) {
                    log.debug("AutoReplenishmentService: Product {} is inactive. Skipping replenishment.", product.getSku());
                    return;
                }

                // Check if stock is strictly below reorder level
                if (product.isLowStockIndicator()) {
                    log.info("AutoReplenishmentService: Product {} is low in stock: {} (reorder level: {})",
                            product.getSku(), product.getStockQuantity(), product.getReorderLevel());

                    // Check if there is already an open purchase order for this product
                    long openOrdersCount = orderRepository.countOpenOrdersForProduct(productId);
                    if (openOrdersCount > 0) {
                        log.info("AutoReplenishmentService: Open purchase orders exist ({}) for product {}. Skipping duplicate PO creation.",
                                openOrdersCount, product.getSku());
                        return;
                    }

                    // Find preferred supplier
                    Supplier supplier = supplierRepository.findPreferredSupplierForProduct(productId).orElse(null);
                    if (supplier == null || !supplier.isActive()) {
                        log.warn("AutoReplenishmentService: Product {} has no active associated supplier. Configure sourcing before replenishment.", product.getSku());
                        return;
                    }

                    Warehouse destinationWarehouse = product.getWarehouse();
                    if (destinationWarehouse == null) {
                        log.warn("AutoReplenishmentService: Product {} has no assigned warehouse. Configure its warehouse before replenishment.", product.getSku());
                        return;
                    }

                    // Reorder Quantity calculation (e.g. reorderLevel * 2, minimum 10 units)
                    int reorderQuantity = (int) Math.min(Integer.MAX_VALUE, Math.max((long) product.getReorderLevel() * 2, 10L));

                    log.info("AutoReplenishmentService: Triggering DRAFT PO for product {} (Quantity: {}) to supplier {} for warehouse {}.",
                            product.getSku(), reorderQuantity, supplier.getName(), destinationWarehouse.getName());

                    OrderItemRequest itemRequest = new OrderItemRequest();
                    itemRequest.setProductId(productId);
                    itemRequest.setQuantity(reorderQuantity);

                    OrderResponse orderResponse = orderService.createSystemOrder(
                            supplier.getId(),
                            destinationWarehouse.getId(),
                            List.of(itemRequest)
                    );

                    log.info("AutoReplenishmentService: Successfully created system auto-replenishment PO: {} (Status: {})",
                            orderResponse.getOrderNumber(), orderResponse.getStatus());
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("AutoReplenishmentService: Error handling product event for auto-replenishment", e);
        }
    }
}

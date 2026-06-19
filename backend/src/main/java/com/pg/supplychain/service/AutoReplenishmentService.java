package com.pg.supplychain.service;

import com.pg.supplychain.dto.OrderItemRequest;
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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoReplenishmentService {

    private final KafkaLiteBroker broker;
    private final OrderService orderService;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final OrderRepository orderRepository;
    private final WarehouseRepository warehouseRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    /**
     * Per-product cooldown timestamps (epoch ms). Prevents burst write amplification during
     * stress tests where thousands of STOCK_ADJUSTED events fire in rapid succession and each
     * would otherwise create a new system PO, bloating the DB across multiple runs.
     */
    private final Map<UUID, Long> replenishmentCooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60_000L; // 60-second per-product cooldown

    @PostConstruct
    public void init() {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        broker.subscribe("product-events", this::handleProductEvent);
        log.info("AutoReplenishmentService: Subscribed to product-events topic.");
    }

    public void handleProductEvent(String messageJson) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
                UUID productId = event.getProductId();
                log.info("AutoReplenishmentService: Received ProductEvent for product={}", productId);

                Product product = productRepository.findById(productId).orElse(null);
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

                    // Cooldown guard: skip if a replenishment was already triggered for this
                    // product within the last COOLDOWN_MS milliseconds. This prevents burst-
                    // driven PO cascades during stress tests where thousands of STOCK_ADJUSTED
                    // events fire simultaneously for the same product set.
                    long now = System.currentTimeMillis();
                    Long lastTriggered = replenishmentCooldowns.get(productId);
                    if (lastTriggered != null && (now - lastTriggered) < COOLDOWN_MS) {
                        log.info("AutoReplenishmentService: Cooldown active for product {} ({}ms remaining). Skipping replenishment.",
                                product.getSku(), COOLDOWN_MS - (now - lastTriggered));
                        return;
                    }
                    // Record cooldown start before any DB work to block concurrent events
                    replenishmentCooldowns.put(productId, now);
                    // Prune stale cooldown entries to prevent unbounded map growth
                    replenishmentCooldowns.entrySet().removeIf(e -> (now - e.getValue()) > COOLDOWN_MS * 2);

                    // Check if there is already an open purchase order for this product
                    long openOrdersCount = orderRepository.countOpenOrdersForProduct(productId);
                    if (openOrdersCount > 0) {
                        log.info("AutoReplenishmentService: Open purchase orders exist ({}) for product {}. Skipping duplicate PO creation.",
                                openOrdersCount, product.getSku());
                        return;
                    }

                    // Find preferred supplier
                    Supplier supplier = supplierRepository.findPreferredSupplierForProduct(productId).orElse(null);
                    if (supplier == null) {
                        log.warn("AutoReplenishmentService: No preferred supplier found for product {}. Searching for default active supplier.", product.getSku());
                        supplier = supplierRepository.findAll().stream()
                                .filter(Supplier::isActive)
                                .findFirst()
                                .orElse(null);
                    }

                    if (supplier == null) {
                        log.error("AutoReplenishmentService: Cannot replenish product {} because no active supplier exists in the system.", product.getSku());
                        return;
                    }

                    // Determine destination warehouse
                    Warehouse destinationWarehouse = product.getWarehouse();
                    if (destinationWarehouse == null) {
                        log.warn("AutoReplenishmentService: Product {} does not have a warehouse assigned. Defaulting to first warehouse.", product.getSku());
                        destinationWarehouse = warehouseRepository.findAll().stream().findFirst().orElse(null);
                    }

                    if (destinationWarehouse == null) {
                        log.error("AutoReplenishmentService: Cannot replenish product {} because no warehouse exists in the system.", product.getSku());
                        return;
                    }

                    // Reorder Quantity calculation (e.g. reorderLevel * 2, minimum 10 units)
                    int reorderQuantity = Math.max(product.getReorderLevel() * 2, 10);

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
            } catch (Exception e) {
                log.error("AutoReplenishmentService: Error handling product event for auto-replenishment", e);
            }
        });
    }
}

package com.pg.supplychain.notification.listener;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.OrderEvent;
import com.pg.supplychain.kafkalite.event.ProductEvent;
import com.pg.supplychain.model.Order;
import com.pg.supplychain.model.Product;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.OrderRepository;
import com.pg.supplychain.repository.ProductRepository;
import com.pg.supplychain.service.NotificationService;
import com.pg.supplychain.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class NotificationEventListener {

    private final KafkaLiteBroker broker;
    private final NotificationService notificationService;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final java.util.Map<java.util.UUID, Long> lowStockNotificationCooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60_000L; // 60-second cooldown

    public NotificationEventListener(
            KafkaLiteBroker broker,
            NotificationService notificationService,
            ProductRepository productRepository,
            OrderRepository orderRepository,
            UserService userService,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.broker = broker;
        this.notificationService = notificationService;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void registerListeners() {
        broker.subscribe("product-events", this::handleProductEvent);
        broker.subscribe("order-events", this::handleOrderEvent);
        log.info("NotificationEventListener: Subscribed to product-events and order-events topics.");
    }

    public void handleProductEvent(String messageJson) {
        try {
            ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
            java.util.UUID productId = event.getProductId();
            log.info("NotificationEventListener: Processing ProductEvent for notification logic. productId={}", productId);

            // 1. Cooldown guard: skip if a notification was triggered recently for this product
            long now = System.currentTimeMillis();
            Long lastTriggered = lowStockNotificationCooldowns.get(productId);
            if (lastTriggered != null && (now - lastTriggered) < COOLDOWN_MS) {
                log.info("NotificationEventListener: Cooldown active for product {}. Skipping notification database lookup.", productId);
                return;
            }

            // 2. Wrap DB checks inside database transaction
            transactionTemplate.executeWithoutResult(status -> {
                Product product = productRepository.findById(productId).orElse(null);
                if (product != null && product.isLowStockIndicator() && product.isActive()) {
                    // Set cooldown and prune old entries
                    lowStockNotificationCooldowns.put(productId, now);
                    lowStockNotificationCooldowns.entrySet().removeIf(e -> (now - e.getValue()) > COOLDOWN_MS * 2);

                    String message = String.format("Product %s is low in stock: %d left (reorder level: %d)",
                            product.getSku(), product.getStockQuantity(), product.getReorderLevel());

                    // Find recipient users: Admins + Warehouse Manager (if configured)
                    Set<User> recipients = new HashSet<>();
                    
                    // Add warehouse manager
                    if (product.getWarehouse() != null && product.getWarehouse().getManager() != null) {
                        recipients.add(product.getWarehouse().getManager());
                    }

                    // Add all admins
                    List<User> admins = userService.getUsersByRole("ROLE_ADMIN");
                    recipients.addAll(admins);

                    for (User user : recipients) {
                        notificationService.createNotification(user, "LOW_STOCK", message);
                    }
                }
            });
        } catch (Exception e) {
            log.error("NotificationEventListener: Failed to process ProductEvent for notifications", e);
        }
    }

    public void handleOrderEvent(String messageJson) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            try {
                OrderEvent event = objectMapper.readValue(messageJson, OrderEvent.class);
                log.info("NotificationEventListener: Processing OrderEvent for notification logic. orderId={}", event.getOrderId());

                Order order = orderRepository.findById(event.getOrderId()).orElse(null);
                if (order != null) {
                    String status = order.getStatus().name();
                    String message = String.format("Purchase Order %s status has been updated to %s", order.getOrderNumber(), status);

                    Set<User> recipients = new HashSet<>();
                    
                    // If it is newly created and PENDING_APPROVAL, we notify Admins for approval
                    if ("PENDING_APPROVAL".equalsIgnoreCase(status)) {
                        message = String.format("Purchase Order %s is pending administrative approval", order.getOrderNumber());
                    }

                    // Add order creator
                    if (order.getCreatedBy() != null) {
                        recipients.add(order.getCreatedBy());
                    }

                    // Add admins
                    List<User> admins = userService.getUsersByRole("ROLE_ADMIN");
                    recipients.addAll(admins);

                    String notificationType = "PENDING_APPROVAL".equalsIgnoreCase(status) ? "ORDER_CREATED" : "ORDER_STATUS_UPDATE";

                    for (User user : recipients) {
                        notificationService.createNotification(user, notificationType, message);
                    }
                }
            } catch (Exception e) {
                log.error("NotificationEventListener: Failed to process OrderEvent for notifications", e);
            }
        });
    }
}

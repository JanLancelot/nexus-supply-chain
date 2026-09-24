package com.nexus.supplychain.notification.listener;

import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.OrderEvent;
import com.nexus.supplychain.kafkalite.event.ProductEvent;
import com.nexus.supplychain.model.Order;
import com.nexus.supplychain.model.Product;
import com.nexus.supplychain.model.User;
import com.nexus.supplychain.repository.OrderRepository;
import com.nexus.supplychain.repository.ProductRepository;
import com.nexus.supplychain.service.NotificationService;
import com.nexus.supplychain.service.UserService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NotificationEventListener {
    private final KafkaLiteBroker broker;
    private final NotificationService notificationService;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Map<UUID, Long> lowStockNotificationCooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60_000L;

    public NotificationEventListener(KafkaLiteBroker broker, NotificationService notificationService,
            ProductRepository productRepository, OrderRepository orderRepository, UserService userService,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
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
        broker.subscribe("product-events", "notifications", this::handleProductEvent);
        broker.subscribe("order-events", "notifications", this::handleOrderEvent);
    }

    public void handleProductEvent(String messageJson) {
        ProductEvent event = objectMapper.readValue(messageJson, ProductEvent.class);
        UUID productId = event.getProductId();
        long now = System.currentTimeMillis();
        Long lastTriggered = lowStockNotificationCooldowns.get(productId);
        if (lastTriggered != null && now - lastTriggered < COOLDOWN_MS) {
            return;
        }
        Boolean notified = transactionTemplate.execute(status -> {
            Product product = productRepository.findById(productId).orElse(null);
            if (product == null || !product.isLowStockIndicator() || !product.isActive()) {
                return false;
            }
            String message = String.format("Product %s is low in stock: %d left (reorder level: %d)",
                    product.getSku(), product.getStockQuantity(), product.getReorderLevel());
            Set<User> recipients = new HashSet<>(userService.getUsersByRole("ROLE_ADMIN"));
            if (product.getWarehouse() != null && product.getWarehouse().getManager() != null) {
                recipients.add(product.getWarehouse().getManager());
            }
            for (User user : recipients) {
                notificationService.createNotificationForEvent(user, "LOW_STOCK", message,
                        sourceEventId(event.getEventId(), "product-events", messageJson));
            }
            return !recipients.isEmpty();
        });
        // A rolled-back attempt must not suppress the broker's retry.
        if (Boolean.TRUE.equals(notified)) {
            lowStockNotificationCooldowns.put(productId, now);
            lowStockNotificationCooldowns.entrySet().removeIf(entry -> now - entry.getValue() > COOLDOWN_MS * 2);
        }
    }

    public void handleOrderEvent(String messageJson) {
        OrderEvent event = objectMapper.readValue(messageJson, OrderEvent.class);
        if (event.getStatus() == null || event.getStatus().isBlank()) {
            throw new IllegalArgumentException("Order event must contain its transition status");
        }
        transactionTemplate.executeWithoutResult(txStatus -> {
            Order order = orderRepository.findById(event.getOrderId()).orElse(null);
            if (order == null) {
                return;
            }
            String status = event.getStatus();
            boolean pending = "PENDING_APPROVAL".equalsIgnoreCase(status);
            String message = pending
                    ? String.format("Purchase Order %s is pending administrative approval", order.getOrderNumber())
                    : String.format("Purchase Order %s status has been updated to %s", order.getOrderNumber(), status);
            Set<User> recipients = new HashSet<>(userService.getUsersByRole("ROLE_ADMIN"));
            if (order.getCreatedBy() != null) {
                recipients.add(order.getCreatedBy());
            }
            for (User user : recipients) {
                notificationService.createNotificationForEvent(user,
                        pending ? "ORDER_CREATED" : "ORDER_STATUS_UPDATE", message,
                        sourceEventId(event.getEventId(), "order-events", messageJson));
            }
        });
    }

    private UUID sourceEventId(UUID eventId, String topic, String messageJson) {
        // Legacy queued messages predate event IDs; retries must still use the same identity.
        return eventId != null ? eventId
                : UUID.nameUUIDFromBytes((topic + ":" + messageJson).getBytes(StandardCharsets.UTF_8));
    }
}

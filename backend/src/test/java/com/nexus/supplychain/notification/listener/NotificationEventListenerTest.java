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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEventListenerTest {
    private final NotificationService notifications = mock(NotificationService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final UserService users = mock(UserService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final User admin = User.builder().id(UUID.randomUUID()).build();
    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(users.getUsersByRole("ROLE_ADMIN")).thenReturn(List.of(admin));
        listener = new NotificationEventListener(mock(KafkaLiteBroker.class), notifications, products, orders,
                users, mapper, transactions);
    }

    @Test
    void queuedOrderTransitionsKeepTheirOwnStatusWhenOrderHasAdvanced() {
        UUID orderId = UUID.randomUUID();
        when(orders.findById(orderId)).thenReturn(Optional.of(Order.builder()
                .id(orderId).orderNumber("PO-42").status(com.nexus.supplychain.model.OrderStatus.DELIVERED).build()));
        var approved = new OrderEvent(orderId, "APPROVED");
        var shipped = new OrderEvent(orderId, "SHIPPED");
        listener.handleOrderEvent(mapper.writeValueAsString(approved));
        listener.handleOrderEvent(mapper.writeValueAsString(shipped));
        verify(notifications).createNotificationForEvent(admin, "ORDER_STATUS_UPDATE",
                "Purchase Order PO-42 status has been updated to APPROVED", approved.getEventId());
        verify(notifications).createNotificationForEvent(admin, "ORDER_STATUS_UPDATE",
                "Purchase Order PO-42 status has been updated to SHIPPED", shipped.getEventId());
    }

    @Test
    void failedLowStockNotificationDoesNotArmTheCooldownBeforeRetry() {
        UUID productId = UUID.randomUUID();
        Product product = Product.builder().id(productId).sku("LOW").stockQuantity(0)
                .reorderLevel(5).isActive(true).build();
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(notifications.createNotificationForEvent(eq(admin), eq("LOW_STOCK"), anyString(), any()))
                .thenThrow(new IllegalStateException("database temporarily unavailable"))
                .thenReturn(null);
        String json = mapper.writeValueAsString(new ProductEvent(productId, "STOCK_ADJUSTED"));
        assertThrows(IllegalStateException.class, () -> listener.handleProductEvent(json));
        listener.handleProductEvent(json);
        verify(notifications, times(2)).createNotificationForEvent(eq(admin), eq("LOW_STOCK"), anyString(), any());
        verify(transactions).rollback(any());
        verify(transactions).commit(any());
    }
}

package com.pg.supplychain;

import com.pg.supplychain.dto.AnalyticsDashboardResponse;
import com.pg.supplychain.dto.NotificationResponse;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.OrderEvent;
import com.pg.supplychain.kafkalite.event.ProductEvent;
import com.pg.supplychain.model.*;
import com.pg.supplychain.repository.*;
import com.pg.supplychain.service.AnalyticsService;
import com.pg.supplychain.service.NotificationService;
import com.pg.supplychain.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AnalyticsAndNotificationTests {

    @Autowired
    private KafkaLiteBroker broker;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private CacheManager cacheManager;

    private User adminUser;
    private User staffUser;

    @Autowired(required = false)
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() throws InterruptedException {
        // Allow background thread to quiescent
        Thread.sleep(500);

        // Clear Redis event queues to isolate test runs (skip if Redis unavailable)
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(List.of(
                        "kafka_topic_product-events",
                        "kafka_topic_order-events",
                        "kafka_topic_audit-events",
                        "kafka_topic_category-events",
                        "kafka_topic_warehouse-events"
                ));
            } catch (Exception e) {
                // Redis not available in test environment — safe to ignore
            }
        }

        adminUser = userRepository.findByEmail("admin@pg.com").orElse(null);
        staffUser = userRepository.findByEmail("staff@pg.com").orElse(null);
        
        // Clear notifications to ensure clean state
        notificationRepository.deleteAll();
        
        // Evict analytics cache
        var cache = cacheManager.getCache("analytics");
        if (cache != null) {
            cache.clear();
        }
    }

    private void setSecurityContext(User user) {
        Map<String, Object> principalDetails = new HashMap<>();
        principalDetails.put("email", user.getEmail());
        principalDetails.put("role", user.getRole().getName());
        principalDetails.put("userId", user.getId().toString());

        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(user.getRole().getName());

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                principalDetails,
                null,
                Collections.singletonList(authority)
        );
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    @Test
    void testLowStockNotificationGeneration() throws InterruptedException {
        setSecurityContext(adminUser);

        // 1. Create a category
        Category category = Category.builder().name("Test Category " + UUID.randomUUID()).build();
        category = categoryRepository.save(category);

        // 2. Create a warehouse with the staff user as manager
        Warehouse warehouse = Warehouse.builder()
                .name("Test Warehouse " + UUID.randomUUID())
                .location("Logistics Zone A")
                .manager(staffUser)
                .build();
        warehouse = warehouseRepository.save(warehouse);

        // 3. Create a low stock product: stock = 2, reorder = 5
        Product product = Product.builder()
                .sku("TST-LOW-" + System.currentTimeMillis())
                .name("Low Stock Item")
                .category(category)
                .warehouse(warehouse)
                .stockQuantity(2)
                .reorderLevel(5)
                .unitPrice(BigDecimal.TEN)
                .isActive(true)
                .build();
        product = productRepository.save(product);

        // 4. Manually trigger a ProductEvent
        broker.send("product-events", new ProductEvent(product.getId(), "CREATED"));

        // Wait up to 5 seconds for async listener to run
        boolean notificationCreated = false;
        for (int i = 0; i < 50; i++) {
            if (notificationRepository.count() > 0) {
                notificationCreated = true;
                break;
            }
            Thread.sleep(100);
        }

        assertTrue(notificationCreated, "Notifications should be generated asynchronously for low-stock product");

        // Verify notification attributes
        List<Notification> notifications = notificationRepository.findAll();
        assertFalse(notifications.isEmpty());
        Notification sample = notifications.stream()
                .filter(n -> "LOW_STOCK".equals(n.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected LOW_STOCK notification not found"));
        assertTrue(sample.getMessage().contains("low in stock"));
    }

    @Test
    @Transactional
    void testAnalyticsAggregationAndCacheEviction() throws InterruptedException {
        setSecurityContext(adminUser);

        // Clear analytics cache first
        var cache = cacheManager.getCache("analytics");
        if (cache != null) {
            cache.clear();
        }

        // Fetch dashboard analytics -> caches it
        AnalyticsDashboardResponse initialResponse = analyticsService.getDashboardAnalytics();
        assertNotNull(initialResponse);

        // Verify cache is populated
        assertNotNull(cache.get("dashboard"), "Analytics dashboard should be cached");

        // Simulate an event to evict the cache
        broker.send("product-events", new ProductEvent(UUID.randomUUID(), "STOCK_ADJUSTED"));

        // Wait up to 5 seconds for background handler to clear cache
        boolean evicted = false;
        for (int i = 0; i < 50; i++) {
            if (cache.get("dashboard") == null) {
                evicted = true;
                break;
            }
            Thread.sleep(100);
        }

        assertTrue(evicted, "Analytics cache should be evicted by the background event listener");
    }

    @Test
    void testNotificationEndpoints() {
        setSecurityContext(adminUser);

        // Create a dummy notification
        notificationService.createNotification(adminUser, "TEST_TYPE", "Hello admin");

        // Get notifications
        com.pg.supplychain.dto.NotificationListResponse listResponse = notificationService.getNotificationsForCurrentUser();
        List<NotificationResponse> responses = listResponse.getNotifications();
        assertFalse(responses.isEmpty());
        assertEquals("TEST_TYPE", responses.get(0).getType());
        assertFalse(responses.get(0).isRead());

        // Mark read
        notificationService.markAsRead(responses.get(0).getId());

        listResponse = notificationService.getNotificationsForCurrentUser();
        responses = listResponse.getNotifications();
        assertTrue(responses.get(0).isRead());
    }
}

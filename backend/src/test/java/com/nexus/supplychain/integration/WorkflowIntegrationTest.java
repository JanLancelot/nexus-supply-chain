package com.nexus.supplychain.integration;

import com.nexus.supplychain.dto.ProductAdjustRequest;
import com.nexus.supplychain.dto.ProductCreateRequest;
import com.nexus.supplychain.dto.ProductResponse;
import com.nexus.supplychain.model.*;
import com.nexus.supplychain.repository.*;
import com.nexus.supplychain.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProductService productService;

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
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Category category;
    private Warehouse warehouse;
    private Supplier supplier;
    private User adminUser;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM supplier_products");
        orderRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        warehouseRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Seed Roles
        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_ADMIN").description("Admin").build()));

        // 2. Seed Admin User
        adminUser = userRepository.save(User.builder()
                .fullName("Admin Integration")
                .email("admin-it@example.test")
                .passwordHash("password")
                .role(adminRole)
                .status("ACTIVE")
                .build());

        // 3. Create core domain resources
        category = categoryRepository.save(Category.builder().name("Integration Category").build());
        warehouse = warehouseRepository.save(Warehouse.builder().name("Integration WH").location("SG").manager(adminUser).build());
        
        supplier = supplierRepository.save(Supplier.builder()
                .name("Integration Supplier")
                .contactPerson("Supplier Person")
                .email("supplier@example.test")
                .isActive(true)
                .leadTimeDays(3)
                .build());
    }

    @Test
    void testEndToEndAutoReplenishmentWorkflow() {
        // Create Product with stock = 10, reorderLevel = 5 (normal stock)
        ProductCreateRequest createRequest = ProductCreateRequest.builder()
                .sku("SKU-INT-AUTO")
                .name("Auto Product")
                .categoryId(category.getId())
                .warehouseId(warehouse.getId())
                .unitPrice(BigDecimal.TEN)
                .reorderLevel(5)
                .isActive(true)
                .build();

        ProductResponse productResponse = productService.createProduct(createRequest);
        assertNotNull(productResponse);
        assertEquals(0, productResponse.getStockQuantity()); // starts with 0 stock, which is < reorderLevel!

        // Link product to supplier as preferred supplier
        jdbcTemplate.update("INSERT INTO supplier_products (supplier_id, product_id, supply_price) VALUES (?, ?, ?)",
                supplier.getId(), productResponse.getId(), BigDecimal.TEN);
        
        // Stock adjustment (add 10 stock to make it normal)
        productService.adjustProductStock(productResponse.getId(), new ProductAdjustRequest(10, "CYCLIC_COUNT_DISCREPANCY"));

        // Wait to clear cool-downs/propagate events if any
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}

        // Reduce stock quantity to 2 (< reorderLevel 5) to trigger auto-replenishment
        productService.adjustProductStock(productResponse.getId(), new ProductAdjustRequest(-8, "CYCLIC_COUNT_DISCREPANCY"));

        // Wait/poll for auto-replenishment system purchase order to be created in DRAFT
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Order> openOrders = orderRepository.findAll();
            assertFalse(openOrders.isEmpty(), "An auto-replenishment purchase order should have been created");
            
            Order autoOrder = openOrders.stream()
                    .filter(o -> o.getOrderNumber().contains("ORD-SYS"))
                    .findFirst()
                    .orElse(null);
            
            assertNotNull(autoOrder, "Auto order should have ORD-SYS prefix");
            assertEquals(OrderStatus.DRAFT, autoOrder.getStatus());
            assertEquals(warehouse.getId(), autoOrder.getWarehouse().getId());
            assertEquals(supplier.getId(), autoOrder.getSupplier().getId());
        });
    }
}

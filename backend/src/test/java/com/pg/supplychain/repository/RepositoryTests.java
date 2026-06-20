package com.pg.supplychain.repository;

import com.pg.supplychain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@ActiveProfiles("test")
class RepositoryTests {

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

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        warehouseRepository.deleteAll();
        userRepository.deleteAll();
    }
    @Test
    void testUserRepository_findByEmailAndRole() {
        Role role = roleRepository.save(Role.builder().name("ROLE_TEST_REPOS").build());
        User user = User.builder()
                .email("repo@pg.com")
                .fullName("Repo User")
                .passwordHash("password")
                .role(role)
                .build();
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("repo@pg.com");
        assertTrue(found.isPresent());
        assertEquals("Repo User", found.get().getFullName());

        List<User> usersByRole = userRepository.findByRoleName("ROLE_TEST_REPOS");
        assertEquals(1, usersByRole.size());
        assertEquals("repo@pg.com", usersByRole.get(0).getEmail());
    }

    @Test
    void testProductRepository_CustomQueries() {
        Category category = categoryRepository.save(Category.builder().name("Repos Category").build());
        Warehouse warehouse = warehouseRepository.save(Warehouse.builder().name("WH Repos").location("Loc").build());

        Product p1 = Product.builder()
                .sku("SKU-REP-1")
                .name("Product 1")
                .category(category)
                .warehouse(warehouse)
                .unitPrice(BigDecimal.TEN)
                .stockQuantity(2)
                .reorderLevel(5) // low stock
                .isActive(true)
                .build();

        Product p2 = Product.builder()
                .sku("SKU-REP-2")
                .name("Product 2")
                .category(category)
                .warehouse(warehouse)
                .unitPrice(BigDecimal.valueOf(20))
                .stockQuantity(10)
                .reorderLevel(5) // normal stock
                .isActive(true)
                .build();

        productRepository.save(p1);
        productRepository.save(p2);

        long lowStockCount = productRepository.countLowStockProducts();
        assertEquals(1, lowStockCount);

        BigDecimal totalVal = productRepository.calculateTotalInventoryValue();
        // 2 * 10 + 10 * 20 = 220
        assertEquals(0, BigDecimal.valueOf(220).compareTo(totalVal));

        List<Object[]> stockByWh = productRepository.countStockByWarehouse();
        assertFalse(stockByWh.isEmpty());
        assertEquals("WH Repos", stockByWh.get(0)[0]);
        assertEquals(12L, ((Number) stockByWh.get(0)[1]).longValue());
    }
}

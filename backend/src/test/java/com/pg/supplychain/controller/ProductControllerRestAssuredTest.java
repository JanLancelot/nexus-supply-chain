package com.pg.supplychain.controller;

import com.pg.supplychain.dto.ProductCreateRequest;
import com.pg.supplychain.integration.BaseIntegrationTest;
import com.pg.supplychain.model.*;
import com.pg.supplychain.repository.CategoryRepository;
import com.pg.supplychain.repository.UserRepository;
import com.pg.supplychain.repository.RoleRepository;
import com.pg.supplychain.security.JwtService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class ProductControllerRestAssuredTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private com.pg.supplychain.repository.ProductRepository productRepository;

    @Autowired
    private com.pg.supplychain.repository.WarehouseRepository warehouseRepository;

    @Autowired
    private com.pg.supplychain.repository.NotificationRepository notificationRepository;

    @Autowired
    private com.pg.supplychain.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private com.pg.supplychain.repository.OrderRepository orderRepository;

    @Autowired
    private com.pg.supplychain.repository.SupplierRepository supplierRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private String adminToken;

    @BeforeEach
    void setUp() {
        System.out.println("DEBUG: Local server port is " + port);
        RestAssured.port = port;
        jdbcTemplate.update("DELETE FROM supplier_products");
        notificationRepository.deleteAll();
        auditLogRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        warehouseRepository.deleteAll();
        userRepository.deleteAll();
        supplierRepository.deleteAll();

        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_ADMIN").build()));

        User admin = userRepository.save(User.builder()
                .fullName("Admin API Test")
                .email("admin-api@pg.com")
                .passwordHash("password")
                .role(adminRole)
                .status("ACTIVE")
                .build());

        adminToken = jwtService.generateToken(admin.getEmail(), "ROLE_ADMIN", admin.getId().toString());
    }

    @Test
    void testGetProducts_Success() {
        given()
                .header("Authorization", "Bearer " + adminToken)
                .accept(ContentType.JSON)
                .when()
                .get("/api/v1/inventory/products")
                .then()
                .statusCode(200)
                .body("content", hasSize(greaterThanOrEqualTo(0)));
    }

    @Test
    void testCreateProduct_UnauthorizedForNonAdmin() {
        // Create token for ROLE_STAFF
        String staffToken = jwtService.generateToken("staff-api@pg.com", "ROLE_STAFF", UUID.randomUUID().toString());

        ProductCreateRequest request = ProductCreateRequest.builder()
                .sku("SKU-RA-UNAUTH")
                .name("Unauth Prod")
                .unitPrice(BigDecimal.TEN)
                .build();

        given()
                .header("Authorization", "Bearer " + staffToken)
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .post("/api/v1/inventory/products")
                .then()
                .statusCode(403);
    }
}

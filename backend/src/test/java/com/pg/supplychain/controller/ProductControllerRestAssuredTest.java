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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        Role staffRole = roleRepository.findByName("ROLE_STAFF")
                .orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_STAFF").build()));
        User staff = userRepository.save(User.builder()
                .fullName("Staff API Test")
                .email("staff-api@pg.com")
                .passwordHash("test-fixture-hash")
                .role(staffRole)
                .status("ACTIVE")
                .build());
        String staffToken = jwtService.generateToken(staff.getEmail(), staffRole.getName(), staff.getId().toString());

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

    @Test
    void createProductDefaultsToActiveWhenTheFrontendOmitsTheFlag() {
        assertProductCreation("", true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void createProductPreservesAnExplicitActiveFlag(boolean active) {
        assertProductCreation(",\"isActive\":" + active, active);
    }

    private void assertProductCreation(String activeProperty, boolean expectedActive) {
        String productId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body("""
                        {"sku":"ACTIVE-CONTRACT","name":"Active Contract Product","unitPrice":12.50,"reorderLevel":0%s}
                        """.formatted(activeProperty))
                .when()
                .post("/api/v1/inventory/products")
                .then()
                .statusCode(201)
                .body("isActive", equalTo(expectedActive))
                .body("stockQuantity", equalTo(0))
                .extract().path("id");

        given()
                .header("Authorization", "Bearer " + adminToken)
                .accept(ContentType.JSON)
                .when()
                .get("/api/v1/inventory/products")
                .then()
                .statusCode(200)
                .body("content.find { it.id == '" + productId + "' }.isActive", equalTo(expectedActive));
    }
}

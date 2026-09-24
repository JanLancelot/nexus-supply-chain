package com.nexus.supplychain.controller;

import com.nexus.supplychain.dto.ProductCreateRequest;
import com.nexus.supplychain.integration.BaseIntegrationTest;
import com.nexus.supplychain.model.*;
import com.nexus.supplychain.repository.CategoryRepository;
import com.nexus.supplychain.repository.UserRepository;
import com.nexus.supplychain.repository.RoleRepository;
import com.nexus.supplychain.security.JwtService;
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
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@org.springframework.test.context.TestPropertySource(properties = "app.seed-demo-data=false")
class ProductControllerRestAssuredTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private com.nexus.supplychain.repository.ProductRepository productRepository;

    @Autowired
    private com.nexus.supplychain.repository.WarehouseRepository warehouseRepository;

    @Autowired
    private com.nexus.supplychain.repository.NotificationRepository notificationRepository;

    @Autowired
    private com.nexus.supplychain.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private com.nexus.supplychain.repository.OrderRepository orderRepository;

    @Autowired
    private com.nexus.supplychain.repository.SupplierRepository supplierRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    private String adminToken;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
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
                .email("admin-api@example.test")
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
                .email("staff-api@example.test")
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

    @Test
    void productsKeepStablePagesAcrossStockUpdatesAndSearchBeyondTheFirstPage() {
        Warehouse warehouse = warehouseRepository.save(Warehouse.builder().name("Picker Warehouse").build());
        List<Product> products = new ArrayList<>();
        for (int i = 0; i < 58; i++) {
            products.add(productRepository.save(Product.builder().sku("PAGE-" + i).name("Product " + i)
                    .warehouse(warehouse).unitPrice(BigDecimal.ONE).isActive(i != 57).build()));
        }
        List<String> expected = products.stream().map(p -> p.getId().toString()).sorted().toList();
        List<String> first = given().header("Authorization", "Bearer " + adminToken)
                .queryParam("size", 50).get("/api/v1/inventory/products")
                .then().statusCode(200).extract().jsonPath().getList("content.id", String.class);
        assertEquals(expected.subList(0, 50), first, "Pages must use stable unique product identity ordering");

        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("quantityAdjustment", 1, "reasonCode", "CYCLIC_COUNT_DISCREPANCY"))
                .post("/api/v1/inventory/products/" + first.get(0) + "/adjust").then().statusCode(200);
        List<String> second = given().header("Authorization", "Bearer " + adminToken)
                .queryParam("page", 1).queryParam("size", 50).get("/api/v1/inventory/products")
                .then().statusCode(200).extract().jsonPath().getList("content.id", String.class);
        List<String> combined = new ArrayList<>(first);
        combined.addAll(second);
        assertEquals(expected, combined);
        assertEquals(58, new HashSet<>(combined).size());

        Product beyondFirstPage = products.stream().filter(p -> p.getId().toString().equals(expected.get(55)))
                .findFirst().orElseThrow();
        given().header("Authorization", "Bearer " + adminToken).queryParam("search", beyondFirstPage.getSku().toLowerCase())
                .queryParam("warehouseId", warehouse.getId()).queryParam("active", beyondFirstPage.isActive())
                .get("/api/v1/inventory/products").then().statusCode(200)
                .body("content.id", hasItem(beyondFirstPage.getId().toString()));
        given().header("Authorization", "Bearer " + adminToken).queryParam("search", "%")
                .get("/api/v1/inventory/products").then().statusCode(200).body("content", hasSize(0));
        given().header("Authorization", "Bearer " + adminToken).queryParam("search", "PAGE-57").queryParam("active", true)
                .get("/api/v1/inventory/products").then().statusCode(200).body("content", hasSize(0));
    }

    @Test
    void orderCreationAndLegacyDeliveryRejectAnotherWarehouseOrMissingWarehouse() {
        Supplier supplier = supplierRepository.save(Supplier.builder().name("Warehouse Supplier").build());
        Warehouse source = warehouseRepository.save(Warehouse.builder().name("Source").build());
        Warehouse destination = warehouseRepository.save(Warehouse.builder().name("Destination").build());
        Product product = productRepository.save(Product.builder().sku("WAREHOUSE-CHECK").name("Stock at source")
                .warehouse(source).stockQuantity(10).unitPrice(BigDecimal.ONE).build());
        Map<String, Object> request = Map.of("supplierId", supplier.getId(), "warehouseId", destination.getId(),
                "items", List.of(Map.of("productId", product.getId(), "quantity", 5)));
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON).body(request)
                .post("/api/v1/orders").then().statusCode(400);
        assertEquals(0, orderRepository.count());

        Order legacy = Order.builder().orderNumber("LEGACY-WRONG-WAREHOUSE").supplier(supplier).warehouse(destination)
                .status(OrderStatus.SHIPPED).totalAmount(BigDecimal.valueOf(5)).build();
        legacy.getItems().add(OrderItem.builder().order(legacy).product(product).quantity(5)
                .unitPrice(BigDecimal.ONE).subtotal(BigDecimal.valueOf(5)).build());
        legacy = orderRepository.save(legacy);
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("status", "DELIVERED")).put("/api/v1/orders/" + legacy.getId() + "/status")
                .then().statusCode(400);
        assertEquals(10, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
        Order unchanged = orderRepository.findById(legacy.getId()).orElseThrow();
        assertEquals(OrderStatus.SHIPPED, unchanged.getStatus());
        assertNull(unchanged.getActualDeliveryDate());
        assertEquals(0, auditLogRepository.count());

        product.setWarehouse(null);
        productRepository.save(product);
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON).body(request)
                .post("/api/v1/orders").then().statusCode(400);
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("status", "DELIVERED")).put("/api/v1/orders/" + legacy.getId() + "/status")
                .then().statusCode(400);
    }

    @Test
    void receiptsAndManualAdjustmentsExposeTheirProvenanceAndOrderItemsIdentifyProducts() {
        Supplier supplier = supplierRepository.save(Supplier.builder().name("Receipt Supplier").build());
        Warehouse warehouse = warehouseRepository.save(Warehouse.builder().name("Receipt Warehouse").build());
        Product product = productRepository.save(Product.builder().sku("RECEIPT-SKU").name("Receipt Product")
                .warehouse(warehouse).unitPrice(BigDecimal.ONE).build());
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("quantityAdjustment", 10, "reasonCode", "CYCLIC_COUNT_DISCREPANCY"))
                .post("/api/v1/inventory/products/" + product.getId() + "/adjust").then().statusCode(200);
        String orderId = given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("supplierId", supplier.getId(), "warehouseId", warehouse.getId(),
                        "items", List.of(Map.of("productId", product.getId(), "quantity", 5))))
                .post("/api/v1/orders").then().statusCode(201)
                .body("items[0].productName", equalTo("Receipt Product"))
                .body("items[0].productSku", equalTo("RECEIPT-SKU")).extract().path("id");
        for (String status : List.of("PENDING_APPROVAL", "APPROVED", "SHIPPED", "DELIVERED")) {
            given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                    .body(Map.of("status", status)).put("/api/v1/orders/" + orderId + "/status")
                    .then().statusCode(200);
        }
        given().header("Authorization", "Bearer " + adminToken).get("/api/v1/audit-logs")
                .then().statusCode(200)
                .body("find { it.action == 'ACTION_MANUAL_ADJUSTMENT' }.newValue", containsString("CYCLIC_COUNT_DISCREPANCY"))
                .body("find { it.action == 'ACTION_ORDER_RECEIPT' }.newValue", containsString(orderId))
                .body("find { it.action == 'ACTION_ORDER_RECEIPT' }.newValue", containsString("quantityReceived"));
        assertEquals(15, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
    }

    @Test
    void analyticsKeepsSameNamedWarehousesSeparate() {
        Warehouse east = warehouseRepository.save(Warehouse.builder().name("Regional").location("East").build());
        Warehouse west = warehouseRepository.save(Warehouse.builder().name("Regional").location("West").build());
        productRepository.save(Product.builder().sku("EAST-STOCK").name("East product").warehouse(east)
                .unitPrice(BigDecimal.ONE).stockQuantity(3).build());
        productRepository.save(Product.builder().sku("WEST-STOCK").name("West product").warehouse(west)
                .unitPrice(BigDecimal.ONE).stockQuantity(7).build());
        given().header("Authorization", "Bearer " + adminToken).get("/api/v1/analytics/dashboard")
                .then().statusCode(200).body("warehouseStockCounts", hasSize(2))
                .body("warehouseStockCounts.find { it.warehouseId == '" + east.getId() + "' }.totalStock", equalTo(3))
                .body("warehouseStockCounts.find { it.warehouseId == '" + east.getId() + "' }.warehouseLocation", equalTo("East"))
                .body("warehouseStockCounts.find { it.warehouseId == '" + west.getId() + "' }.totalStock", equalTo(7));
    }

    @Test
    void anEmptySupplierSetupCanCreateValidatedSuppliersAndManageExplicitSourcing() {
        given().header("Authorization", "Bearer " + adminToken).get("/api/v1/suppliers")
                .then().statusCode(200).body("", hasSize(0));
        Product product = productRepository.save(Product.builder().sku("SOURCED-PRODUCT").name("Sourced Product")
                .unitPrice(BigDecimal.valueOf(12.50)).build());
        String supplierId = given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("name", "First Supplier", "email", "supplier@example.com"))
                .post("/api/v1/suppliers").then().statusCode(201)
                .body("active", equalTo(true)).body("leadTimeDays", equalTo(3)).body("productIds", hasSize(0))
                .extract().path("id");
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("productIds", List.of(product.getId())))
                .put("/api/v1/suppliers/" + supplierId + "/products").then().statusCode(200)
                .body("productIds", contains(product.getId().toString()));
        assertEquals(UUID.fromString(supplierId), supplierRepository.findPreferredSupplierForProduct(product.getId()).orElseThrow().getId());
        jdbcTemplate.update("UPDATE supplier_products SET supply_price = 1.23 WHERE supplier_id = ?", UUID.fromString(supplierId));
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("productIds", List.of(product.getId())))
                .put("/api/v1/suppliers/" + supplierId + "/products").then().statusCode(200);
        assertEquals(0, new BigDecimal("1.23").compareTo(jdbcTemplate.queryForObject(
                "SELECT supply_price FROM supplier_products WHERE supplier_id = ?", BigDecimal.class, UUID.fromString(supplierId))));
        given().header("Authorization", "Bearer " + adminToken).get("/api/v1/suppliers")
                .then().statusCode(200).body("[0].productIds", contains(product.getId().toString()));
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("name", "First Supplier")).post("/api/v1/suppliers").then().statusCode(400);
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("name", "Invalid Supplier", "email", "invalid", "leadTimeDays", -1))
                .post("/api/v1/suppliers").then().statusCode(400);
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("name", "Long Phone", "phone", "1".repeat(51)))
                .post("/api/v1/suppliers").then().statusCode(400);
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("productIds", List.of(UUID.randomUUID())))
                .put("/api/v1/suppliers/" + supplierId + "/products").then().statusCode(404);
        assertEquals(List.of(product.getId()), supplierRepository.findProductIds(UUID.fromString(supplierId)));
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body(Map.of("productIds", List.of())).put("/api/v1/suppliers/" + supplierId + "/products")
                .then().statusCode(200).body("productIds", hasSize(0));
        assertTrue(supplierRepository.findPreferredSupplierForProduct(product.getId()).isEmpty());
    }

    @Test
    void supplierProvisioningAndSourcingAreAdminOnly() {
        Role staffRole = roleRepository.findByName("ROLE_STAFF").orElseGet(() -> roleRepository.save(Role.builder().name("ROLE_STAFF").build()));
        User staff = userRepository.save(User.builder().fullName("Supplier Staff").email("supplier-staff@example.com")
                .passwordHash("test-fixture-hash").role(staffRole).status("ACTIVE").build());
        String token = jwtService.generateToken(staff.getEmail(), "ROLE_STAFF", staff.getId().toString());
        given().header("Authorization", "Bearer " + token).get("/api/v1/suppliers").then().statusCode(200);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).body(Map.of("name", "No Staff Creation"))
                .post("/api/v1/suppliers").then().statusCode(403);
        given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON).body(Map.of("productIds", List.of()))
                .put("/api/v1/suppliers/" + UUID.randomUUID() + "/products").then().statusCode(403);
    }

    @Test
    void newProductsRejectAMissingOrUnknownStockWarehouse() {
        Map<String, Object> request = new java.util.HashMap<>(Map.of("sku", "NO-WAREHOUSE", "name", "Missing warehouse",
                "unitPrice", 1, "reorderLevel", 0));
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON).body(request)
                .post("/api/v1/inventory/products").then().statusCode(400);
        request.put("warehouseId", UUID.randomUUID());
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON).body(request)
                .post("/api/v1/inventory/products").then().statusCode(404);
        assertEquals(0, productRepository.count());
        assertEquals(0, auditLogRepository.count());
    }

    private void assertProductCreation(String activeProperty, boolean expectedActive) {
        Warehouse warehouse = warehouseRepository.save(Warehouse.builder().name("Product contract warehouse").build());
        String productId = given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body("""
                        {"sku":"ACTIVE-CONTRACT","name":"Active Contract Product","unitPrice":12.50,"reorderLevel":0,"warehouseId":"%s"%s}
                        """.formatted(warehouse.getId(), activeProperty))
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

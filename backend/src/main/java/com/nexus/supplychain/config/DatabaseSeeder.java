package com.nexus.supplychain.config;

import com.nexus.supplychain.model.*;
import com.nexus.supplychain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final WarehouseRepository warehouseRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;

    @Value("${app.bootstrap.admin-email:}")
    private String adminEmail;

    @Value("${app.bootstrap.admin-password:}")
    private String adminPassword;

    @Value("${app.bootstrap.admin-name:Administrator}")
    private String adminName;

    @Value("${app.seed-demo-data:false}")
    private boolean seedDemoData;

    @Value("${app.bootstrap.staff-password:}")
    private String staffPassword;

    @Override
    public void run(String... args) {
        Role staffRole = ensureRole("ROLE_STAFF", "Inventory Operations Staff");
        Role adminRole = ensureRole("ROLE_ADMIN", "Supply Chain Administrator");

        if (userRepository.count() == 0) {
            if (!adminEmail.isBlank() || !adminPassword.isBlank()) {
                if (adminEmail.isBlank() || !adminEmail.contains("@") || adminEmail.length() > 255) {
                    throw new IllegalStateException("Set a valid APP_BOOTSTRAP_ADMIN_EMAIL to bootstrap an administrator");
                }
                validateBootstrapPassword(adminPassword, "APP_BOOTSTRAP_ADMIN_PASSWORD");
                userRepository.save(User.builder()
                        .fullName(adminName)
                        .email(adminEmail)
                        .passwordHash(passwordEncoder.encode(adminPassword))
                        .role(adminRole)
                        .status("ACTIVE")
                        .build());
                log.info("Bootstrap administrator created");
            } else {
                log.warn("No users exist. Supply bootstrap administrator credentials to create the first account.");
            }
        }

        if (!seedDemoData) {
            return;
        }
        User adminUser = userRepository.findByRoleName("ROLE_ADMIN").stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Create a bootstrap administrator before enabling demo data"));
        if (userRepository.findByEmail("staff@example.test").isEmpty()) {
            validateBootstrapPassword(staffPassword, "APP_BOOTSTRAP_STAFF_PASSWORD");
            userRepository.save(User.builder()
                    .fullName("Demo Staff")
                    .email("staff@example.test")
                    .passwordHash(passwordEncoder.encode(staffPassword))
                    .role(staffRole)
                    .status("ACTIVE")
                    .build());
        }

        if (warehouseRepository.count() == 0) {
            log.info("Seeding default warehouses...");
            Warehouse mainWh = Warehouse.builder()
                    .name("Main Distribution Center")
                    .location("Singapore")
                    .manager(adminUser)
                    .build();

            Warehouse regionalWh = Warehouse.builder()
                    .name("Regional Fulfillment Hub")
                    .location("Kuala Lumpur")
                    .manager(adminUser)
                    .build();

            warehouseRepository.save(mainWh);
            warehouseRepository.save(regionalWh);
        }

        if (categoryRepository.count() == 0) {
            log.info("Seeding default product categories...");
            Category electronics = Category.builder()
                    .name("Electronics")
                    .description("Devices, components, and computing accessories")
                    .build();

            Category industrial = Category.builder()
                    .name("Industrial")
                    .description("Factory tools, machinery components, and safety equipment")
                    .build();

            Category office = Category.builder()
                    .name("Office Supplies")
                    .description("Furniture, stationery, and general office goods")
                    .build();

            categoryRepository.save(electronics);
            categoryRepository.save(industrial);
            categoryRepository.save(office);
        }

        if (supplierRepository.count() == 0) {
            log.info("Seeding default suppliers...");
            Supplier apex = Supplier.builder()
                    .name("Apex Logistics & Supplies")
                    .contactPerson("John Doe")
                    .email("contact@apex.com")
                    .phone("+65 6123 4567")
                    .address("10 Anson Rd, Singapore")
                    .isActive(true)
                    .build();

            Supplier globalParts = Supplier.builder()
                    .name("Global Tech Parts")
                    .contactPerson("Jane Smith")
                    .email("sales@globalparts.com")
                    .phone("+60 3 8888 8888")
                    .address("Jalan Ampang, Kuala Lumpur")
                    .isActive(true)
                    .build();

            supplierRepository.save(apex);
            supplierRepository.save(globalParts);
        }

        if (productRepository.count() == 0) {
            log.info("Seeding default products...");
            Category electronics = categoryRepository.findByName("Electronics").orElse(null);
            Category industrial = categoryRepository.findByName("Industrial").orElse(null);
            Category office = categoryRepository.findByName("Office Supplies").orElse(null);
            Warehouse mainWh = warehouseRepository.findAll().stream()
                    .filter(w -> w.getName().contains("Main"))
                    .findFirst()
                    .orElse(null);
            Warehouse regionalWh = warehouseRepository.findAll().stream()
                    .filter(w -> w.getName().contains("Regional"))
                    .findFirst()
                    .orElse(null);

            Product router = Product.builder()
                    .sku("SKU-ELEC-001")
                    .name("High-Performance Router")
                    .description("Enterprise grade 10Gbps dual-band fiber router")
                    .category(electronics)
                    .unitPrice(BigDecimal.valueOf(299.99))
                    .stockQuantity(100)
                    .reorderLevel(15)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            Product valve = Product.builder()
                    .sku("SKU-IND-999")
                    .name("Steel Pressure Valve")
                    .description("Industrial grade high pressure gas valve")
                    .category(industrial)
                    .unitPrice(BigDecimal.valueOf(89.50))
                    .stockQuantity(100)
                    .reorderLevel(10)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            Product laptop = Product.builder()
                    .sku("SKU-ELEC-002")
                    .name("Developer Laptop")
                    .description("High-end workstation laptop")
                    .category(electronics)
                    .unitPrice(BigDecimal.valueOf(1499.00))
                    .stockQuantity(100)
                    .reorderLevel(5)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            Product switchGear = Product.builder()
                    .sku("SKU-ELEC-003")
                    .name("Network Switch")
                    .description("24-port Gigabit managed switch")
                    .category(electronics)
                    .unitPrice(BigDecimal.valueOf(189.99))
                    .stockQuantity(100)
                    .reorderLevel(10)
                    .warehouse(regionalWh)
                    .isActive(true)
                    .build();

            Product safetyHelmet = Product.builder()
                    .sku("SKU-IND-001")
                    .name("Industrial Safety Helmet")
                    .description("Heavy duty construction safety helmet")
                    .category(industrial)
                    .unitPrice(BigDecimal.valueOf(24.99))
                    .stockQuantity(200)
                    .reorderLevel(20)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            Product gloves = Product.builder()
                    .sku("SKU-IND-002")
                    .name("Work Gloves")
                    .description("Cut-resistant protective work gloves")
                    .category(industrial)
                    .unitPrice(BigDecimal.valueOf(12.50))
                    .stockQuantity(500)
                    .reorderLevel(50)
                    .warehouse(regionalWh)
                    .isActive(true)
                    .build();

            Product desk = Product.builder()
                    .sku("SKU-OFF-001")
                    .name("Ergonomic Desk")
                    .description("Adjustable height standing desk")
                    .category(office)
                    .unitPrice(BigDecimal.valueOf(349.99))
                    .stockQuantity(50)
                    .reorderLevel(5)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            Product chair = Product.builder()
                    .sku("SKU-OFF-002")
                    .name("Office Chair")
                    .description("Ergonomic mesh office chair")
                    .category(office)
                    .unitPrice(BigDecimal.valueOf(199.99))
                    .stockQuantity(75)
                    .reorderLevel(8)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            Product paper = Product.builder()
                    .sku("SKU-OFF-003")
                    .name("Printer Paper")
                    .description("A4 copy paper ream")
                    .category(office)
                    .unitPrice(BigDecimal.valueOf(5.99))
                    .stockQuantity(1000)
                    .reorderLevel(100)
                    .warehouse(regionalWh)
                    .isActive(true)
                    .build();

            Product monitor = Product.builder()
                    .sku("SKU-ELEC-004")
                    .name("4K Monitor")
                    .description("27-inch 4K UHD professional monitor")
                    .category(electronics)
                    .unitPrice(BigDecimal.valueOf(399.99))
                    .stockQuantity(60)
                    .reorderLevel(10)
                    .warehouse(regionalWh)
                    .isActive(true)
                    .build();

            productRepository.save(router);
            productRepository.save(valve);
            productRepository.save(laptop);
            productRepository.save(switchGear);
            productRepository.save(safetyHelmet);
            productRepository.save(gloves);
            productRepository.save(desk);
            productRepository.save(chair);
            productRepository.save(paper);
            productRepository.save(monitor);
            log.info("Products seeded successfully.");
        }
    }

    private Role ensureRole(String name, String description) {
        return roleRepository.findByName(name).orElseGet(() -> roleRepository.save(
                Role.builder().name(name).description(description).build()));
    }

    private void validateBootstrapPassword(String password, String setting) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException(setting + " must be at least 12 characters and at most 72 UTF-8 bytes");
        }
    }
}

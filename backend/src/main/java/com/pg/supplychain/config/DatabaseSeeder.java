package com.pg.supplychain.config;

import com.pg.supplychain.model.*;
import com.pg.supplychain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
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

    @Override
    public void run(String... args) {
        // 1. Seed Roles
        if (roleRepository.count() == 0) {
            log.info("No roles found in database. Seeding default roles...");
            Role staffRole = Role.builder()
                    .name("ROLE_STAFF")
                    .description("Inventory Operations Staff")
                    .build();

            Role adminRole = Role.builder()
                    .name("ROLE_ADMIN")
                    .description("Supply Chain Administrator")
                    .build();

            roleRepository.save(staffRole);
            roleRepository.save(adminRole);
            log.info("Roles seeded successfully.");
        }

        // 2. Seed Users
        if (userRepository.count() == 0) {
            log.info("No users found in database. Seeding default accounts...");

            Role staffRole = roleRepository.findByName("ROLE_STAFF")
                    .orElseThrow(() -> new IllegalStateException("ROLE_STAFF not found"));

            Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                    .orElseThrow(() -> new IllegalStateException("ROLE_ADMIN not found"));

            User staff = User.builder()
                    .fullName("Staff User")
                    .email("staff@pg.com")
                    .passwordHash(passwordEncoder.encode("StaffPassword123"))
                    .role(staffRole)
                    .status("ACTIVE")
                    .build();

            User admin = User.builder()
                    .fullName("Admin User")
                    .email("admin@pg.com")
                    .passwordHash(passwordEncoder.encode("AdminPassword123"))
                    .role(adminRole)
                    .status("ACTIVE")
                    .build();

            userRepository.save(staff);
            userRepository.save(admin);

            log.info("Default accounts seeded successfully: staff@pg.com, admin@pg.com");
        } else {
            log.info("Database users already exist. Skipping seeding.");
        }

        // Retrieve admin user for warehouse manager mapping
        User adminUser = userRepository.findByEmail("admin@pg.com").orElse(null);

        // 3. Seed Warehouses
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

        // 4. Seed Categories
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
                    .description("Furniture, stationary, and general office goods")
                    .build();

            categoryRepository.save(electronics);
            categoryRepository.save(industrial);
            categoryRepository.save(office);
        }

        // 5. Seed Suppliers
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

        // 6. Seed Products
        if (productRepository.count() == 0) {
            log.info("Seeding default products...");
            Category electronics = categoryRepository.findByName("Electronics").orElse(null);
            Category industrial = categoryRepository.findByName("Industrial").orElse(null);
            Warehouse mainWh = warehouseRepository.findAll().stream()
                    .filter(w -> w.getName().contains("Main"))
                    .findFirst()
                    .orElse(null);

            Product router = Product.builder()
                    .sku("SKU-ELEC-001")
                    .name("High-Performance Router")
                    .description("Enterprise grade 10Gbps dual-band fiber router")
                    .category(electronics)
                    .unitPrice(BigDecimal.valueOf(299.99))
                    .stockQuantity(50)
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
                    .stockQuantity(8)
                    .reorderLevel(10)
                    .warehouse(mainWh)
                    .isActive(true)
                    .build();

            productRepository.save(router);
            productRepository.save(valve);
            log.info("Products seeded successfully.");
        }
    }
}

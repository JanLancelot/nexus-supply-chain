package com.pg.supplychain.config;

import com.pg.supplychain.model.Role;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.RoleRepository;
import com.pg.supplychain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

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
    }
}

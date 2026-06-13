package com.pg.supplychain.config;

import com.pg.supplychain.model.User;
import com.pg.supplychain.model.UserRole;
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
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() == 0) {
            log.info("No users found in database. Seeding default accounts...");

            User staff = User.builder()
                    .email("staff@pg.com")
                    .passwordHash(passwordEncoder.encode("StaffPassword123"))
                    .role(UserRole.ROLE_STAFF)
                    .build();

            User admin = User.builder()
                    .email("admin@pg.com")
                    .passwordHash(passwordEncoder.encode("AdminPassword123"))
                    .role(UserRole.ROLE_ADMIN)
                    .build();

            userRepository.save(staff);
            userRepository.save(admin);

            log.info("Default accounts seeded successfully: staff@pg.com, admin@pg.com");
        } else {
            log.info("Database users already exist. Skipping seeding.");
        }
    }
}

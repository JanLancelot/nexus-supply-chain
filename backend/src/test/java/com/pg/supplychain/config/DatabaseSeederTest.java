package com.pg.supplychain.config;

import com.pg.supplychain.model.Role;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseSeederTest {
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final SupplierRepository suppliers = mock(SupplierRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private DatabaseSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new DatabaseSeeder(users, roles, encoder, warehouses, categories, suppliers, products);
        ReflectionTestUtils.setField(seeder, "adminEmail", "");
        ReflectionTestUtils.setField(seeder, "adminPassword", "");
        ReflectionTestUtils.setField(seeder, "adminName", "Administrator");
        ReflectionTestUtils.setField(seeder, "staffPassword", "");
        when(roles.findByName("ROLE_ADMIN")).thenReturn(Optional.of(Role.builder().name("ROLE_ADMIN").build()));
        when(roles.findByName("ROLE_STAFF")).thenReturn(Optional.of(Role.builder().name("ROLE_STAFF").build()));
    }

    @Test
    void neverCreatesKnownPasswordAccountsOrDemoInventoryByDefault() {
        seeder.run();
        verify(users, never()).save(any(User.class));
        verifyNoInteractions(encoder, warehouses, categories, suppliers, products);
    }

    @Test
    void bootstrapsAdministratorUsingSuppliedPasswordOnly() {
        ReflectionTestUtils.setField(seeder, "adminEmail", "owner@example.test");
        ReflectionTestUtils.setField(seeder, "adminPassword", "a-generated-password");
        when(encoder.encode("a-generated-password")).thenReturn("encoded");
        seeder.run();
        verify(users).save(argThat(user -> user.getEmail().equals("owner@example.test")
                && user.getPasswordHash().equals("encoded") && user.getRole().getName().equals("ROLE_ADMIN")));
        verifyNoInteractions(warehouses, categories, suppliers, products);
    }

    @Test
    void doesNotOverwriteExistingAccounts() {
        when(users.count()).thenReturn(1L);
        seeder.run();
        verify(users, never()).save(any(User.class));
        verifyNoInteractions(encoder);
    }

    @Test
    void demoDataCannotCreateAStaffOnlyDatabaseThatBlocksAdminBootstrap() {
        ReflectionTestUtils.setField(seeder, "seedDemoData", true);
        assertThrows(IllegalStateException.class, () -> seeder.run());
        verify(users, never()).save(any(User.class));
        verifyNoInteractions(encoder, warehouses, categories, suppliers, products);
    }

    @Test
    void rejectsMissingOrWeakBootstrapPasswords() {
        ReflectionTestUtils.setField(seeder, "adminEmail", "owner@example.test");
        assertThrows(IllegalStateException.class, () -> seeder.run());
        ReflectionTestUtils.setField(seeder, "adminPassword", "short");
        assertThrows(IllegalStateException.class, () -> seeder.run());
        verify(users, never()).save(any(User.class));
    }
}

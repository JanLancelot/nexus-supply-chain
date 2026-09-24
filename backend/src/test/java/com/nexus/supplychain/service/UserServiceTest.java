package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.UserCreateRequest;
import com.nexus.supplychain.dto.UserResponse;
import com.nexus.supplychain.exception.BadRequestException;
import com.nexus.supplychain.model.Role;
import com.nexus.supplychain.model.User;
import com.nexus.supplychain.repository.RoleRepository;
import com.nexus.supplychain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetUserById_Null() {
        assertNull(userService.getUserById(null));
        verifyNoInteractions(userRepository);
    }

    @Test
    void testGetUserById_RefreshesAccountState() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).email("test@example.test").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        User result1 = userService.getUserById(id);
        assertNotNull(result1);
        assertEquals("test@example.test", result1.getEmail());

        // Account changes must be visible on the next lookup.
        User result2 = userService.getUserById(id);
        assertSame(result1, result2);
        verify(userRepository, times(2)).findById(id);
    }

    @Test
    void testGetUsersByRole_Null() {
        assertTrue(userService.getUsersByRole(null).isEmpty());
        verifyNoInteractions(userRepository);
    }

    @Test
    void testGetUsersByRole_RefreshesRoleMembership() {
        String roleName = "ROLE_ADMIN";
        User user = User.builder().id(UUID.randomUUID()).email("test@example.test").build();
        when(userRepository.findByRoleName(roleName)).thenReturn(Arrays.asList(user));

        List<User> result1 = userService.getUsersByRole(roleName);
        assertEquals(1, result1.size());

        List<User> result2 = userService.getUsersByRole(roleName);
        assertSame(result1, result2);
        verify(userRepository, times(2)).findByRoleName(roleName);
    }

    @Test
    void testCreateUser_Success() {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("New User")
                .email("newuser@example.test")
                .password("password123")
                .roleName("ROLE_STAFF")
                .build();

        Role role = Role.builder().id(UUID.randomUUID()).name("ROLE_STAFF").build();
        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("New User")
                .email("newuser@example.test")
                .passwordHash("encoded_password")
                .role(role)
                .status("ACTIVE")
                .build();

        when(userRepository.findByEmail("newuser@example.test")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_STAFF")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserResponse response = userService.createUser(request);

        assertNotNull(response);
        assertEquals(savedUser.getId(), response.getId());
        assertEquals("newuser@example.test", response.getEmail());
        assertEquals("ROLE_STAFF", response.getRole());

        // Verify audit logging
        verify(auditService, times(1)).logChange(
                eq("User"), eq(savedUser.getId()), eq("CREATE"), isNull(), anyMap()
        );
    }

    @Test
    void testCreateUser_DuplicateEmail() {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("New User")
                .email("duplicate@example.test")
                .password("password123")
                .roleName("ROLE_STAFF")
                .build();

        User existingUser = User.builder().email("duplicate@example.test").build();
        when(userRepository.findByEmail("duplicate@example.test")).thenReturn(Optional.of(existingUser));

        assertThrows(BadRequestException.class, () -> userService.createUser(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void testCreateUser_RoleNotFound() {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("New User")
                .email("new@example.test")
                .password("password123")
                .roleName("ROLE_NONEXISTENT")
                .build();

        when(userRepository.findByEmail("new@example.test")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_NONEXISTENT")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> userService.createUser(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void testGetAllUsers() {
        Role role = Role.builder().name("ROLE_ADMIN").build();
        User u1 = User.builder().id(UUID.randomUUID()).fullName("A").email("a@example.test").role(role).build();
        User u2 = User.builder().id(UUID.randomUUID()).fullName("B").email("b@example.test").role(role).build();

        when(userRepository.findAll()).thenReturn(Arrays.asList(u1, u2));

        List<UserResponse> responses = userService.getAllUsers();
        assertEquals(2, responses.size());
        assertEquals("a@example.test", responses.get(0).getEmail());
        assertEquals("b@example.test", responses.get(1).getEmail());
    }
}

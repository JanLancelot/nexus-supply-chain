package com.pg.supplychain.service;

import com.pg.supplychain.dto.UserCreateRequest;
import com.pg.supplychain.dto.UserResponse;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.model.Role;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.RoleRepository;
import com.pg.supplychain.repository.UserRepository;
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
    void testGetUserById_CachedAndUncached() {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).email("test@pg.com").build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        User result1 = userService.getUserById(id);
        assertNotNull(result1);
        assertEquals("test@pg.com", result1.getEmail());

        // Call again to verify cache hit (no second repo call)
        User result2 = userService.getUserById(id);
        assertSame(result1, result2);
        verify(userRepository, times(1)).findById(id);
    }

    @Test
    void testGetUsersByRole_Null() {
        assertTrue(userService.getUsersByRole(null).isEmpty());
        verifyNoInteractions(userRepository);
    }

    @Test
    void testGetUsersByRole_CachedAndUncached() {
        String roleName = "ROLE_ADMIN";
        User user = User.builder().id(UUID.randomUUID()).email("test@pg.com").build();
        when(userRepository.findByRoleName(roleName)).thenReturn(Arrays.asList(user));

        List<User> result1 = userService.getUsersByRole(roleName);
        assertEquals(1, result1.size());

        List<User> result2 = userService.getUsersByRole(roleName);
        assertSame(result1, result2);
        verify(userRepository, times(1)).findByRoleName(roleName);
    }

    @Test
    void testCreateUser_Success() {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("New User")
                .email("newuser@pg.com")
                .password("password123")
                .roleName("ROLE_STAFF")
                .build();

        Role role = Role.builder().id(UUID.randomUUID()).name("ROLE_STAFF").build();
        User savedUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("New User")
                .email("newuser@pg.com")
                .passwordHash("encoded_password")
                .role(role)
                .status("ACTIVE")
                .build();

        // Prime the role cache first to verify it gets cleared
        when(userRepository.findByRoleName("ROLE_STAFF")).thenReturn(List.of());
        userService.getUsersByRole("ROLE_STAFF"); // now cached

        when(userRepository.findByEmail("newuser@pg.com")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_STAFF")).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("password123")).thenReturn("encoded_password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        UserResponse response = userService.createUser(request);

        assertNotNull(response);
        assertEquals(savedUser.getId(), response.getId());
        assertEquals("newuser@pg.com", response.getEmail());
        assertEquals("ROLE_STAFF", response.getRole());

        // Verify userCache is updated (no repository call when fetching by ID now)
        User cachedUser = userService.getUserById(savedUser.getId());
        assertNotNull(cachedUser);
        assertEquals("newuser@pg.com", cachedUser.getEmail());
        verify(userRepository, never()).findById(savedUser.getId());

        // Verify roleCache was invalidated (forces a repository call on next read)
        when(userRepository.findByRoleName("ROLE_STAFF")).thenReturn(List.of(savedUser));
        List<User> rolesResult = userService.getUsersByRole("ROLE_STAFF");
        assertEquals(1, rolesResult.size());
        verify(userRepository, times(2)).findByRoleName("ROLE_STAFF"); // 1st before create, 2nd after cache invalidation

        // Verify audit logging
        verify(auditService, times(1)).logChange(
                eq("User"), eq(savedUser.getId()), eq("CREATE"), isNull(), anyMap()
        );
    }

    @Test
    void testCreateUser_DuplicateEmail() {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("New User")
                .email("duplicate@pg.com")
                .password("password123")
                .roleName("ROLE_STAFF")
                .build();

        User existingUser = User.builder().email("duplicate@pg.com").build();
        when(userRepository.findByEmail("duplicate@pg.com")).thenReturn(Optional.of(existingUser));

        assertThrows(BadRequestException.class, () -> userService.createUser(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void testCreateUser_RoleNotFound() {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("New User")
                .email("new@pg.com")
                .password("password123")
                .roleName("ROLE_NONEXISTENT")
                .build();

        when(userRepository.findByEmail("new@pg.com")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_NONEXISTENT")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> userService.createUser(request));
        verify(userRepository, never()).save(any());
    }

    @Test
    void testGetAllUsers() {
        Role role = Role.builder().name("ROLE_ADMIN").build();
        User u1 = User.builder().id(UUID.randomUUID()).fullName("A").email("a@pg.com").role(role).build();
        User u2 = User.builder().id(UUID.randomUUID()).fullName("B").email("b@pg.com").role(role).build();

        when(userRepository.findAll()).thenReturn(Arrays.asList(u1, u2));

        List<UserResponse> responses = userService.getAllUsers();
        assertEquals(2, responses.size());
        assertEquals("a@pg.com", responses.get(0).getEmail());
        assertEquals("b@pg.com", responses.get(1).getEmail());
    }
}

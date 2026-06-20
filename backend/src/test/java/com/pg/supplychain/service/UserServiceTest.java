package com.pg.supplychain.service;

import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

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
}

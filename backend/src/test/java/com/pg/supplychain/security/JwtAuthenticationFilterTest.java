package com.pg.supplychain.security;

import com.pg.supplychain.model.Role;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {
    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwt = new JwtService();
    private final UUID id = UUID.randomUUID();
    private User user;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        ReflectionTestUtils.setField(jwt, "secretKey", "test-only-filter-key-not-for-production-12345");
        ReflectionTestUtils.setField(jwt, "jwtExpiration", 3600000L);
        filter = new JwtAuthenticationFilter(jwt, users);
        user = User.builder().id(id).email("user@example.test")
                .role(Role.builder().name("ROLE_STAFF").build()).build();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesCurrentRoleInsteadOfStaleAdminClaim() throws Exception {
        when(users.findById(id)).thenReturn(Optional.of(user));
        authenticate(jwt.generateToken(user.getEmail(), "ROLE_ADMIN", id.toString()));
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertEquals("ROLE_STAFF", authentication.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void rejectsDisabledAndDeletedAccounts() throws Exception {
        user.setStatus("DISABLED");
        when(users.findById(id)).thenReturn(Optional.of(user), Optional.empty());
        String token = jwt.generateToken(user.getEmail(), "ROLE_ADMIN", id.toString());
        authenticate(token);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        authenticate(token);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rejectsChangedEmailAndMalformedUserIds() throws Exception {
        when(users.findById(id)).thenReturn(Optional.of(user));
        authenticate(jwt.generateToken("old@example.test", "ROLE_ADMIN", id.toString()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        authenticate(jwt.generateToken(user.getEmail(), "ROLE_ADMIN", "not-a-uuid"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rejectsTamperedAndExpiredTokens() throws Exception {
        authenticate("not-a-token");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        ReflectionTestUtils.setField(jwt, "jwtExpiration", -1000L);
        authenticate(jwt.generateToken(user.getEmail(), "ROLE_ADMIN", id.toString()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(users);
    }

    private void authenticate(String token) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
    }
}

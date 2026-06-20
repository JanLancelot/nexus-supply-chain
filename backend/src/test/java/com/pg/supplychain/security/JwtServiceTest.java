package com.pg.supplychain.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private final String secretKey = generateRandomSecret();

    private static String generateRandomSecret() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", secretKey);
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 3600000L); // 1 hour
    }

    @Test
    void testGenerateAndExtractClaims() {
        String username = "testuser@pg.com";
        String role = "ROLE_ADMIN";
        String userId = "12345678-1234-1234-1234-123456789abc";

        String token = jwtService.generateToken(username, role, userId);
        assertNotNull(token);

        assertEquals(username, jwtService.extractUsername(token));
        assertEquals(role, jwtService.extractRole(token));
        assertEquals(userId, jwtService.extractUserId(token));
    }

    @Test
    void testTokenValidation() {
        String username = "testuser@pg.com";
        String role = "ROLE_ADMIN";
        String userId = "12345678-1234-1234-1234-123456789abc";

        String token = jwtService.generateToken(username, role, userId);

        assertTrue(jwtService.isTokenValid(token, username));
        assertFalse(jwtService.isTokenValid(token, "otheruser@pg.com"));
        assertTrue(jwtService.isTokenValid(token));
    }

    @Test
    void testExpiredToken() {
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", -1000L); // expired 1s ago
        String token = jwtService.generateToken("testuser", "ROLE_USER", "123");

        assertFalse(jwtService.isTokenValid(token));
    }
}

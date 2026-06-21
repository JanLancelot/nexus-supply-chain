package com.pg.supplychain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.supplychain.dto.LoginRequest;
import com.pg.supplychain.model.Role;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.UserRepository;
import com.pg.supplychain.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerMockMvcTest {

    private MockMvc mockMvc;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthController authController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    void testLogin_Success() throws Exception {
        UUID userId = UUID.randomUUID();
        Role role = Role.builder().name("ROLE_ADMIN").build();
        User user = User.builder()
                .id(userId)
                .email("admin@pg.com")
                .passwordHash("hashed")
                .role(role)
                .build();

        when(userRepository.findByEmail("admin@pg.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtService.generateToken(org.mockito.ArgumentMatchers.eq("admin@pg.com"), org.mockito.ArgumentMatchers.eq("ROLE_ADMIN"), org.mockito.ArgumentMatchers.eq(userId.toString()), org.mockito.ArgumentMatchers.any())).thenReturn("mocked-jwt-token");

        LoginRequest request = new LoginRequest("admin@pg.com", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mocked-jwt-token"));
    }

    @Test
    void testLogin_Failure() throws Exception {
        when(userRepository.findByEmail("wrong@pg.com")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("wrong@pg.com", "wrongpass");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }
}

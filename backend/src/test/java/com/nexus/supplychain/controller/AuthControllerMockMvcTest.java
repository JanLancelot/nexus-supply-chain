package com.nexus.supplychain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.supplychain.dto.LoginRequest;
import com.nexus.supplychain.model.Role;
import com.nexus.supplychain.model.User;
import com.nexus.supplychain.repository.UserRepository;
import com.nexus.supplychain.security.JwtService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
                .email("admin@example.test")
                .passwordHash("hashed")
                .role(role)
                .build();

        when(userRepository.findByEmail("admin@example.test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtService.generateToken(org.mockito.ArgumentMatchers.eq("admin@example.test"), org.mockito.ArgumentMatchers.eq("ROLE_ADMIN"), org.mockito.ArgumentMatchers.eq(userId.toString()), org.mockito.ArgumentMatchers.any())).thenReturn("mocked-jwt-token");

        LoginRequest request = new LoginRequest("admin@example.test", "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mocked-jwt-token"));
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        User user = User.builder().email("disabled@example.test").passwordHash("hashed").status("DISABLED").build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(user.getEmail(), "password123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        verifyNoInteractions(jwtService);
    }

    @Test
    void rejectsPasswordsBeyondBcryptByteLimit() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("test@example.test", "é".repeat(37)))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void testLogin_Failure() throws Exception {
        when(userRepository.findByEmail("wrong@example.test")).thenReturn(Optional.empty());

        LoginRequest request = new LoginRequest("wrong@example.test", "wrongpass");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
        verify(passwordEncoder).matches(eq("wrongpass"), anyString());
    }
}

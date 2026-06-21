package com.pg.supplychain.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pg.supplychain.dto.UserCreateRequest;
import com.pg.supplychain.dto.UserResponse;
import com.pg.supplychain.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerMockMvcTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(userController).build();
    }

    @Test
    void testCreateUser_Success() throws Exception {
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("Test User")
                .email("test@pg.com")
                .password("password123")
                .roleName("ROLE_STAFF")
                .build();

        UserResponse response = UserResponse.builder()
                .id(UUID.randomUUID())
                .fullName("Test User")
                .email("test@pg.com")
                .roleName("ROLE_STAFF")
                .status("ACTIVE")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("test@pg.com"))
                .andExpect(jsonPath("$.fullName").value("Test User"))
                .andExpect(jsonPath("$.roleName").value("ROLE_STAFF"));
    }

    @Test
    void testCreateUser_InvalidRequest() throws Exception {
        // Missing fields or invalid email
        UserCreateRequest request = UserCreateRequest.builder()
                .fullName("")
                .email("invalid-email")
                .password("short")
                .roleName("")
                .build();

        mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetAllUsers() throws Exception {
        UserResponse response1 = UserResponse.builder()
                .id(UUID.randomUUID())
                .fullName("User One")
                .email("one@pg.com")
                .roleName("ROLE_ADMIN")
                .build();

        UserResponse response2 = UserResponse.builder()
                .id(UUID.randomUUID())
                .fullName("User Two")
                .email("two@pg.com")
                .roleName("ROLE_STAFF")
                .build();

        when(userService.getAllUsers()).thenReturn(Arrays.asList(response1, response2));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size()").value(2))
                .andExpect(jsonPath("$[0].email").value("one@pg.com"))
                .andExpect(jsonPath("$[1].email").value("two@pg.com"));
    }
}

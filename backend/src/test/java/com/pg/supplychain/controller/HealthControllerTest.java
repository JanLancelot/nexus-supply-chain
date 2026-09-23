package com.pg.supplychain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTest {
    @Test
    void doesNotExposeDatabaseExceptions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new RuntimeException("private database connection details"));
        var response = new HealthController(jdbc).healthCheck();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().get("status"));
        assertFalse(response.getBody().containsKey("error"));
        assertFalse(response.getBody().toString().contains("private"));
    }
}

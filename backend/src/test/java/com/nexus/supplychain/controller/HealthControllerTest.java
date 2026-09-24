package com.nexus.supplychain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTest {
    @Test
    void doesNotExposeDatabaseExceptions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new RuntimeException("private database connection details"));
        var response = new HealthController(jdbc, mock(com.nexus.supplychain.kafkalite.KafkaLiteBroker.class), mock(org.springframework.cache.CacheManager.class)).healthCheck();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("DOWN", response.getBody().get("status"));
        assertFalse(response.getBody().containsKey("error"));
        assertFalse(response.getBody().toString().contains("private"));
    }
    @Test
    void reportsOptionalFallbackAsDegradedAndRequiredBrokerFailureAsUnavailable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        var broker = mock(com.nexus.supplychain.kafkalite.KafkaLiteBroker.class);
        when(broker.healthStatus()).thenReturn("MEMORY_FALLBACK");
        var controller = new HealthController(jdbc, broker, mock(org.springframework.cache.CacheManager.class));
        var fallback = controller.healthCheck();
        assertEquals(200, fallback.getStatusCode().value());
        assertEquals("DEGRADED", fallback.getBody().get("status"));
        assertEquals("MEMORY_FALLBACK", fallback.getBody().get("events"));
        when(broker.isRequired()).thenReturn(true);
        when(broker.healthStatus()).thenReturn("KAFKA_DOWN");
        var unavailable = controller.healthCheck();
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("HEALTHY", unavailable.getBody().get("database"));
    }
}

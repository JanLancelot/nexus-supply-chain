package com.nexus.supplychain.controller;

import com.nexus.supplychain.config.ResilientCacheManager;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final KafkaLiteBroker broker;
    private final CacheManager cacheManager;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result == null || result != 1) {
                response.put("status", "DOWN");
                response.put("database", "UNEXPECTED_RESPONSE");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
            }
            response.put("database", "HEALTHY");
        } catch (Exception failure) {
            response.put("status", "DOWN");
            response.put("database", "UNHEALTHY");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
        String events;
        try {
            events = broker.healthStatus();
        } catch (RuntimeException failure) {
            events = "UNHEALTHY";
        }
        String cache = cacheManager instanceof ResilientCacheManager resilient
                ? resilient.healthStatus() : "UNKNOWN";
        response.put("events", events);
        response.put("cache", cache);
        response.put("eventsRequired", broker.isRequired());
        if (broker.isRequired() && !"KAFKA_UP".equals(events)) {
            response.put("status", "DOWN");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }
        boolean healthy = "KAFKA_UP".equals(events) && "UP".equals(cache);
        response.put("status", healthy ? "UP" : "DEGRADED");
        return ResponseEntity.ok(response);
    }
}

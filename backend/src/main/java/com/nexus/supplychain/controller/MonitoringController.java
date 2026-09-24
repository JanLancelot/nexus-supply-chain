package com.nexus.supplychain.controller;

import com.nexus.supplychain.dto.MonitoringResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class MonitoringController {
    private final MonitoringResponse configuration;

    public MonitoringController(@Value("${app.monitoring.grafana-url:}") String grafanaUrl) {
        String validated = validatedUrl(grafanaUrl);
        configuration = new MonitoringResponse(validated, validated != null);
    }

    @GetMapping("/api/v1/monitoring")
    public MonitoringResponse monitoring() {
        return configuration;
    }

    private static String validatedUrl(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (!http || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() < -1 || uri.getPort() > 65535) {
                return null;
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}

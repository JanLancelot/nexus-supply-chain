package com.pg.supplychain.controller;

import com.pg.supplychain.dto.AnalyticsDashboardResponse;
import com.pg.supplychain.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard")
    public ResponseEntity<AnalyticsDashboardResponse> getDashboard() {
        return ResponseEntity.ok(analyticsService.getDashboardAnalytics());
    }
}

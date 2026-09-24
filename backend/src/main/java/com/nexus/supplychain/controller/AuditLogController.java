package com.nexus.supplychain.controller;

import com.nexus.supplychain.dto.AuditLogResponse;
import com.nexus.supplychain.service.AuditService;
import lombok.RequiredArgsConstructor;
import com.nexus.supplychain.exception.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<AuditLogResponse>> getAllAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        if (page < 0 || size < 1) {
            throw new BadRequestException("Page must be non-negative and size must be positive");
        }
        int limitSize = Math.min(size, 50);
        Pageable pageable = PageRequest.of(page, limitSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return ResponseEntity.ok(auditService.getAllAuditLogs(pageable));
    }
}


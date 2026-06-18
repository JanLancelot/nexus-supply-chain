package com.pg.supplychain.controller;

import com.pg.supplychain.model.AuditLog;
import com.pg.supplychain.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.Page;
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
    public ResponseEntity<List<AuditLog>> getAllAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int limitSize = Math.min(size, 50);
        Pageable pageable = PageRequest.of(page, limitSize, Sort.by("createdAt").descending());
        return ResponseEntity.ok(auditService.getAllAuditLogs(pageable));
    }
}


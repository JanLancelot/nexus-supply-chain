package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.AuditLogResponse;
import com.nexus.supplychain.model.AuditLog;
import com.nexus.supplychain.repository.AuditLogRepository;
import com.nexus.supplychain.security.SecurityContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogRepository auditLogRepository;
    private final SecurityContextService securityContextService;
    private final ObjectMapper objectMapper;

    /** Required audit evidence joins the business mutation and rolls back with it. */
    @Transactional
    public void logChange(String entityType, UUID entityId, String action, Object oldValue, Object newValue) {
        auditLogRepository.save(AuditLog.builder()
                .user(securityContextService.getCurrentUser())
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldValue == null ? null : objectMapper.writeValueAsString(oldValue))
                .newValue(newValue == null ? null : objectMapper.writeValueAsString(newValue))
                .build());
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAllAuditLogs(Pageable pageable) {
        return auditLogRepository.findBy(pageable).stream().map(entry -> AuditLogResponse.builder()
                .id(entry.getId()).userId(entry.getUserId()).entityType(entry.getEntityType())
                .entityId(entry.getEntityId()).action(entry.getAction())
                .oldValue(entry.getOldValue()).newValue(entry.getNewValue()).createdAt(entry.getCreatedAt())
                .build()).toList();
    }
}

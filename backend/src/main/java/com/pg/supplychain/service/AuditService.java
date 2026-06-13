package com.pg.supplychain.service;

import com.pg.supplychain.model.AuditLog;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.AuditLogRepository;
import com.pg.supplychain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public void logChange(String entityType, UUID entityId, String action, Object oldValue, Object newValue) {
        String oldStr = null;
        String newStr = null;

        try {
            if (oldValue != null) {
                oldStr = objectMapper.writeValueAsString(oldValue);
            }
            if (newValue != null) {
                newStr = objectMapper.writeValueAsString(newValue);
            }
        } catch (Exception e) {
            log.error("Failed to serialize audit log values for entity: " + entityType, e);
        }

        User actor = getCurrentUser();

        AuditLog auditLog = AuditLog.builder()
                .user(actor)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldStr)
                .newValue(newStr)
                .createdAt(OffsetDateTime.now())
                .build();

        auditLogRepository.save(auditLog);
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Map) {
            Map<?, ?> principal = (Map<?, ?>) auth.getPrincipal();
            String userIdStr = (String) principal.get("userId");
            if (userIdStr != null) {
                try {
                    return userRepository.findById(UUID.fromString(userIdStr)).orElse(null);
                } catch (IllegalArgumentException e) {
                    log.error("Invalid actor UUID format in security context: " + userIdStr);
                }
            }
        }
        return null;
    }
}

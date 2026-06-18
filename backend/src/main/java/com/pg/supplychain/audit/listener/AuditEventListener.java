package com.pg.supplychain.audit.listener;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.AuditEvent;
import com.pg.supplychain.model.AuditLog;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.AuditLogRepository;
import com.pg.supplychain.service.UserService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventListener {

    private final KafkaLiteBroker broker;
    private final AuditLogRepository auditLogRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void registerListener() {
        broker.subscribe("audit-events", this::handleAuditEvent);
        log.info("AuditEventListener: Subscribed to audit-events topic.");
    }

    private void handleAuditEvent(String messageJson) {
        try {
            AuditEvent event = objectMapper.readValue(messageJson, AuditEvent.class);
            log.info("AuditEventListener: Received AuditEvent: action={}", event.getAction());

            User user = null;
            if (event.getActorId() != null) {
                user = userService.getUserById(event.getActorId());
            }

            String oldStr = null;
            String newStr = null;
            if (event.getOldValue() != null) {
                oldStr = objectMapper.writeValueAsString(event.getOldValue());
            }
            if (event.getNewValue() != null) {
                newStr = objectMapper.writeValueAsString(event.getNewValue());
            }

            AuditLog logEntry = AuditLog.builder()
                    .user(user)
                    .entityType(event.getEntityType())
                    .entityId(event.getEntityId())
                    .action(event.getAction())
                    .oldValue(oldStr)
                    .newValue(newStr)
                    .createdAt(OffsetDateTime.now())
                    .build();

            auditLogRepository.save(logEntry);
            log.debug("AuditEventListener: Saved audit log asynchronously for action={}", event.getAction());
        } catch (Exception e) {
            log.error("AuditEventListener: Failed to process AuditEvent message", e);
        }
    }
}

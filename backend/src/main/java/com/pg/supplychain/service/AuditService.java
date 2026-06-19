package com.pg.supplychain.service;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.AuditEvent;
import com.pg.supplychain.model.AuditLog;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.AuditLogRepository;
import com.pg.supplychain.security.SecurityContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final SecurityContextService securityContextService;
    private final KafkaLiteBroker kafkaLiteBroker;

    public void logChange(String entityType, UUID entityId, String action, Object oldValue, Object newValue) {
        User actor = securityContextService.getCurrentUser();
        UUID actorId = (actor != null) ? actor.getId() : null;

        AuditEvent event = AuditEvent.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .actorId(actorId)
                .build();

        log.info("AuditService: Publishing audit log event to kafka-lite. action={}", action);
        kafkaLiteBroker.send("audit-events", event);
    }

    public List<AuditLog> getAllAuditLogs(Pageable pageable) {
        return auditLogRepository.findBy(pageable);
    }
}


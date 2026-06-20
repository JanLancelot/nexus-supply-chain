package com.pg.supplychain.service;

import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.AuditEvent;
import com.pg.supplychain.model.AuditLog;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.AuditLogRepository;
import com.pg.supplychain.security.SecurityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private SecurityContextService securityContextService;
    @Mock private KafkaLiteBroker kafkaLiteBroker;

    @InjectMocks
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testLogChange_WithActor() {
        UUID actorId = UUID.randomUUID();
        User actor = User.builder().id(actorId).email("actor@pg.com").build();
        when(securityContextService.getCurrentUser()).thenReturn(actor);

        UUID entityId = UUID.randomUUID();
        auditService.logChange("Product", entityId, "CREATE", null, "New Value");

        verify(kafkaLiteBroker, times(1)).send(eq("audit-events"), any(AuditEvent.class));
    }

    @Test
    void testLogChange_WithoutActor() {
        when(securityContextService.getCurrentUser()).thenReturn(null);

        UUID entityId = UUID.randomUUID();
        auditService.logChange("Product", entityId, "CREATE", null, "New Value");

        verify(kafkaLiteBroker, times(1)).send(eq("audit-events"), any(AuditEvent.class));
    }

    @Test
    void testGetAllAuditLogs() {
        Pageable pageable = Pageable.unpaged();
        AuditLog log = AuditLog.builder().id(UUID.randomUUID()).action("CREATE").build();
        when(auditLogRepository.findBy(pageable)).thenReturn(Collections.singletonList(log));

        List<AuditLog> result = auditService.getAllAuditLogs(pageable);
        assertEquals(1, result.size());
        assertEquals("CREATE", result.get(0).getAction());
    }
}

package com.nexus.supplychain.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.nexus.supplychain.dto.AuditLogResponse;
import com.nexus.supplychain.model.AuditLog;
import com.nexus.supplychain.model.User;
import com.nexus.supplychain.repository.AuditLogRepository;
import com.nexus.supplychain.security.SecurityContextService;
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
    @org.mockito.Spy private ObjectMapper objectMapper = JsonMapper.builder().build();

    @InjectMocks
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testLogChange_WithActor() {
        UUID actorId = UUID.randomUUID();
        User actor = User.builder().id(actorId).email("actor@example.test").build();
        when(securityContextService.getCurrentUser()).thenReturn(actor);

        UUID entityId = UUID.randomUUID();
        auditService.logChange("Product", entityId, "CREATE", null, "New Value");

        verify(auditLogRepository).save(argThat(entry ->
                entityId.equals(entry.getEntityId()) && "CREATE".equals(entry.getAction())
                        && "\"New Value\"".equals(entry.getNewValue())));
    }

    @Test
    void testLogChange_WithoutActor() {
        when(securityContextService.getCurrentUser()).thenReturn(null);

        UUID entityId = UUID.randomUUID();
        auditService.logChange("Product", entityId, "CREATE", null, "New Value");

        verify(auditLogRepository).save(argThat(entry ->
                entityId.equals(entry.getEntityId()) && "CREATE".equals(entry.getAction())
                        && "\"New Value\"".equals(entry.getNewValue())));
    }

    @Test
    void testGetAllAuditLogs() {
        Pageable pageable = Pageable.unpaged();
        AuditLog log = AuditLog.builder().id(UUID.randomUUID()).action("CREATE").build();
        when(auditLogRepository.findBy(pageable)).thenReturn(Collections.singletonList(log));

        List<AuditLogResponse> result = auditService.getAllAuditLogs(pageable);
        assertEquals(1, result.size());
        assertEquals("CREATE", result.get(0).getAction());
    }
    @Test
    void persistenceFailuresPropagateToTheMutation() {
        when(auditLogRepository.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () ->
                auditService.logChange("Product", UUID.randomUUID(), "UPDATE", null, "new"));
    }
}

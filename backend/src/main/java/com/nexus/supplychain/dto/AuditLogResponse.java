package com.nexus.supplychain.dto;

import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.UUID;

@Value
@Builder
public class AuditLogResponse {
    UUID id;
    UUID userId;
    String entityType;
    UUID entityId;
    String action;
    String oldValue;
    String newValue;
    OffsetDateTime createdAt;
}

package com.pg.supplychain.kafkalite.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {
    private String entityType;
    private UUID entityId;
    private String action;
    private Object oldValue;
    private Object newValue;
    private UUID actorId;
}

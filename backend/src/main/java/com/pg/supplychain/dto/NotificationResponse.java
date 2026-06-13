package com.pg.supplychain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {
    private UUID id;
    private UUID userId;
    private String type;
    private String message;
    private boolean isRead;
    private OffsetDateTime createdAt;
}

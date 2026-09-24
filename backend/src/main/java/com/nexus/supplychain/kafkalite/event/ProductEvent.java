package com.nexus.supplychain.kafkalite.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductEvent {
    private UUID eventId;
    private UUID productId;
    private String action;
    public ProductEvent(UUID productId, String action) {
        this(UUID.randomUUID(), productId, action);
    }

    public static class ProductEventBuilder {
        private UUID eventId = UUID.randomUUID();
    }
}

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
public class OrderEvent {
    private UUID eventId;
    private UUID orderId;
    private String status;
    public OrderEvent(UUID orderId, String status) {
        this(UUID.randomUUID(), orderId, status);
    }

    public static class OrderEventBuilder {
        private UUID eventId = UUID.randomUUID();
    }
}

package com.nexus.supplychain.dto;

import lombok.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierResponse {
    private UUID id;
    private String name;
    private String contactPerson;
    private String email;
    private String phone;
    private String address;
    private boolean active;
    private Integer leadTimeDays;
    private OffsetDateTime createdAt;
    private List<UUID> productIds;
}

package com.pg.supplychain.dto;

import lombok.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponse {
    private UUID id;
    private String fullName;
    private String email;
    private String role;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}

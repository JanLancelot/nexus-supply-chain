package com.pg.supplychain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseRequest {

    @NotBlank(message = "Warehouse name is required")
    private String name;

    private String location;

    private UUID managerId;
}

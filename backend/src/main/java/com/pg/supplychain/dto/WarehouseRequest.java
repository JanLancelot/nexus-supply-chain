package com.pg.supplychain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseRequest {

    @NotBlank(message = "Warehouse name is required")
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String location;

    private UUID managerId;
}

package com.pg.supplychain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductAdjustRequest {

    @NotNull(message = "Quantity adjustment is required")
    private Integer quantityAdjustment;

    @NotBlank(message = "Reason code is required")
    private String reasonCode;
}

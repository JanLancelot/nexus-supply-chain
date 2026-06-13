package com.pg.supplychain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductCreateRequest {

    @NotBlank(message = "SKU is required")
    private String sku;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    private UUID categoryId;

    @NotNull(message = "Unit price is required")
    @Min(value = 0, message = "Unit price must be positive or zero")
    private BigDecimal unitPrice;

    @NotNull(message = "Reorder level is required")
    @Min(value = 0, message = "Reorder level must be positive or zero")
    private Integer reorderLevel;

    private UUID warehouseId;

    @Builder.Default
    private boolean isActive = true;
}

package com.pg.supplychain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Digits;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductCreateRequest {

    @NotBlank(message = "SKU is required")
    @Size(max = 50)
    private String sku;

    @NotBlank(message = "Name is required")
    @Size(max = 255)
    private String name;

    @Size(max = 255)
    private String description;

    private UUID categoryId;

    @NotNull(message = "Unit price is required")
    @Min(value = 0, message = "Unit price must be positive or zero")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal unitPrice;

    @NotNull(message = "Reorder level is required")
    @Min(value = 0, message = "Reorder level must be positive or zero")
    private Integer reorderLevel;

    private UUID warehouseId;

    @Builder.Default
    private boolean isActive = true;

    @JsonProperty("isActive")
    public boolean isActive() {
        return isActive;
    }

    @JsonProperty("isActive")
    public void setActive(boolean active) {
        this.isActive = active;
    }
}

package com.nexus.supplychain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierProductsRequest {
    @NotNull
    @Size(max = 500)
    private List<@NotNull UUID> productIds;
}

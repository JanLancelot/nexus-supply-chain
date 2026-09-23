package com.pg.supplychain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCreateRequest {

    @NotNull(message = "Supplier ID is required")
    private UUID supplierId;

    @NotNull(message = "Warehouse ID is required")
    private UUID warehouseId;

    @NotEmpty(message = "Order must contain at least one item")
    @Size(max = 500, message = "Order must contain at most 500 items")
    @Valid
    private List<@NotNull OrderItemRequest> items;
}

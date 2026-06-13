package com.pg.supplychain.dto;

import lombok.*;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {
    private UUID id;
    private String sku;
    private String name;
    private int stockQuantity;
    private int reorderLevel;
    private boolean lowStockIndicator;
}

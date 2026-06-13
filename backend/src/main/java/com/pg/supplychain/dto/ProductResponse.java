package com.pg.supplychain.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {
    private UUID id;
    private String sku;
    private String name;
    private String description;
    private UUID categoryId;
    private String categoryName;
    private BigDecimal unitPrice;
    private int stockQuantity;
    private int reorderLevel;
    private UUID warehouseId;
    private String warehouseName;
    private boolean lowStockIndicator;
    private boolean isActive;
}

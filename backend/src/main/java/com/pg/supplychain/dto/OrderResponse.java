package com.pg.supplychain.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {
    private UUID id;
    private String orderNumber;
    private UUID supplierId;
    private String supplierName;
    private UUID warehouseId;
    private String warehouseName;
    private String status;
    private BigDecimal totalAmount;
    private UUID createdBy;
    private List<OrderItemResponse> items;
}

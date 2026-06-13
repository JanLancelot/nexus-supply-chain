package com.pg.supplychain.dto;

import lombok.*;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {
    private UUID id;
    private String supplierName;
    private String status;
    private UUID createdBy;
    private List<OrderItemResponse> items;
}

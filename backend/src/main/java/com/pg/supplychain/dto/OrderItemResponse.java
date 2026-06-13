package com.pg.supplychain.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemResponse {
    private UUID productId;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
}

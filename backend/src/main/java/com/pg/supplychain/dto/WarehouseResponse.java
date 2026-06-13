package com.pg.supplychain.dto;

import lombok.*;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseResponse {
    private UUID id;
    private String name;
    private String location;
    private UUID managerId;
    private String managerName;
}

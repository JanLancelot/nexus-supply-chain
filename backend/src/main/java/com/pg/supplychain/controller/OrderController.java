package com.pg.supplychain.controller;

import com.pg.supplychain.dto.OrderCreateRequest;
import com.pg.supplychain.dto.OrderResponse;
import com.pg.supplychain.dto.OrderStatusUpdateRequest;
import com.pg.supplychain.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse created = orderService.createOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable UUID id,
            @Valid @RequestBody OrderStatusUpdateRequest request
    ) {
        OrderResponse updated = orderService.updateOrderStatus(id, request);
        return ResponseEntity.ok(updated);
    }
}

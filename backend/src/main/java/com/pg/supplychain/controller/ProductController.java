package com.pg.supplychain.controller;

import com.pg.supplychain.dto.ProductAdjustRequest;
import com.pg.supplychain.dto.ProductCreateRequest;
import com.pg.supplychain.dto.ProductResponse;
import com.pg.supplychain.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<List<ProductResponse>> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int limitSize = Math.min(size, 200);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, limitSize);
        return ResponseEntity.ok(productService.getAllProducts(pageable));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductCreateRequest request) {
        ProductResponse created = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/adjust")
    public ResponseEntity<ProductResponse> adjustProductStock(
            @PathVariable UUID id,
            @Valid @RequestBody ProductAdjustRequest request
    ) {
        ProductResponse updated = productService.adjustProductStock(id, request);
        return ResponseEntity.ok(updated);
    }
}

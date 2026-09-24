package com.nexus.supplychain.controller;

import com.nexus.supplychain.dto.PagedResponse;
import com.nexus.supplychain.dto.ProductAdjustRequest;
import com.nexus.supplychain.dto.ProductCreateRequest;
import com.nexus.supplychain.dto.ProductResponse;
import com.nexus.supplychain.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.nexus.supplychain.exception.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<PagedResponse<ProductResponse>> getAllProducts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) Boolean active
    ) {
        if (page < 0 || size < 1) {
            throw new BadRequestException("Page must be non-negative and size must be positive");
        }
        if (search != null && search.length() > 255) {
            throw new BadRequestException("Search must not exceed 255 characters");
        }
        int limitSize = Math.min(size, 50);
        return ResponseEntity.ok(productService.getAllProducts(page, limitSize, search, warehouseId, active));
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

package com.nexus.supplychain.controller;

import com.nexus.supplychain.dto.SupplierCreateRequest;
import com.nexus.supplychain.dto.SupplierProductsRequest;
import com.nexus.supplychain.dto.SupplierResponse;
import com.nexus.supplychain.service.SupplierService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
public class SupplierController {
    private final SupplierService supplierService;

    @GetMapping
    public ResponseEntity<List<SupplierResponse>> getAllSuppliers() {
        return ResponseEntity.ok(supplierService.getAllSuppliers());
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> createSupplier(@Valid @RequestBody SupplierCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(supplierService.createSupplier(request));
    }

    @GetMapping("/{id}/products")
    public ResponseEntity<SupplierProductsRequest> getProducts(@PathVariable UUID id) {
        return ResponseEntity.ok(supplierService.getProducts(id));
    }

    @PutMapping("/{id}/products")
    public ResponseEntity<SupplierProductsRequest> updateProducts(@PathVariable UUID id,
            @Valid @RequestBody SupplierProductsRequest request) {
        return ResponseEntity.ok(supplierService.updateProducts(id, request));
    }
}

package com.pg.supplychain.service;

import com.pg.supplychain.dto.ProductAdjustRequest;
import com.pg.supplychain.dto.ProductCreateRequest;
import com.pg.supplychain.dto.ProductResponse;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.model.Product;
import com.pg.supplychain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final AuditService auditService;

    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "CYCLIC_COUNT_DISCREPANCY",
            "DAMAGED_GOODS_SCRAP",
            "SUPPLIER_SHORTAGE"
    );

    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        if (productRepository.findBySku(request.getSku()).isPresent()) {
            throw new BadRequestException("SKU already exists: " + request.getSku());
        }

        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .stockQuantity(0)
                .reorderLevel(request.getReorderLevel())
                .build();

        Product savedProduct = productRepository.save(product);

        // Audit log product creation
        auditService.logChange(
                "Product",
                savedProduct.getId(),
                "ACTION_CREATE_PRODUCT",
                null,
                mapToAuditState(savedProduct)
        );

        return mapToResponse(savedProduct);
    }

    @Transactional
    public ProductResponse adjustProductStock(UUID id, ProductAdjustRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));

        if (!ALLOWED_REASON_CODES.contains(request.getReasonCode())) {
            throw new BadRequestException("Invalid reason code: " + request.getReasonCode());
        }

        int oldStock = product.getStockQuantity();
        int newStock = oldStock + request.getQuantityAdjustment();

        if (newStock < 0) {
            throw new BadRequestException("Adjustment would result in negative stock quantity: " + newStock);
        }

        // Capture previous stock value state for auditing
        Map<String, Object> oldState = new HashMap<>();
        oldState.put("stockQuantity", oldStock);

        product.setStockQuantity(newStock);
        Product updatedProduct = productRepository.save(product);

        // Capture new stock value state for auditing
        Map<String, Object> newState = new HashMap<>();
        newState.put("stockQuantity", newStock);

        auditService.logChange(
                "Product",
                id,
                "ACTION_MANUAL_ADJUSTMENT",
                oldState,
                newState
        );

        return mapToResponse(updatedProduct);
    }

    private ProductResponse mapToResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .stockQuantity(product.getStockQuantity())
                .reorderLevel(product.getReorderLevel())
                .lowStockIndicator(product.isLowStockIndicator())
                .build();
    }

    private Map<String, Object> mapToAuditState(Product product) {
        Map<String, Object> state = new HashMap<>();
        state.put("sku", product.getSku());
        state.put("name", product.getName());
        state.put("stockQuantity", product.getStockQuantity());
        state.put("reorderLevel", product.getReorderLevel());
        return state;
    }
}

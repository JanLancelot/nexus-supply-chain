package com.pg.supplychain.service;

import com.pg.supplychain.dto.ProductAdjustRequest;
import com.pg.supplychain.dto.ProductCreateRequest;
import com.pg.supplychain.dto.ProductResponse;
import com.pg.supplychain.exception.BadRequestException;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.kafkalite.KafkaLiteBroker;
import com.pg.supplychain.kafkalite.event.ProductEvent;
import com.pg.supplychain.model.Category;
import com.pg.supplychain.model.Product;
import com.pg.supplychain.model.Warehouse;
import com.pg.supplychain.repository.CategoryRepository;
import com.pg.supplychain.repository.ProductRepository;
import com.pg.supplychain.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final CategoryRepository categoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final AuditService auditService;
    private final KafkaLiteBroker kafkaLiteBroker;

    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "CYCLIC_COUNT_DISCREPANCY",
            "DAMAGED_GOODS_SCRAP",
            "SUPPLIER_SHORTAGE"
    );

    @Cacheable(value = "products", key = "'all'")
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

        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + request.getCategoryId()));
        }

        Warehouse warehouse = null;
        if (request.getWarehouseId() != null) {
            warehouse = warehouseRepository.findById(request.getWarehouseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + request.getWarehouseId()));
        }

        Product product = Product.builder()
                .sku(request.getSku())
                .name(request.getName())
                .description(request.getDescription())
                .category(category)
                .unitPrice(request.getUnitPrice() != null ? request.getUnitPrice() : BigDecimal.ZERO)
                .stockQuantity(0)
                .reorderLevel(request.getReorderLevel())
                .warehouse(warehouse)
                .isActive(request.isActive())
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

        // Publish event to kafka-lite
        kafkaLiteBroker.send("product-events", new ProductEvent(savedProduct.getId(), "CREATED"));

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

        Map<String, Object> oldState = new HashMap<>();
        oldState.put("stockQuantity", oldStock);

        product.setStockQuantity(newStock);
        Product updatedProduct = productRepository.save(product);

        Map<String, Object> newState = new HashMap<>();
        newState.put("stockQuantity", newStock);

        auditService.logChange(
                "Product",
                id,
                "ACTION_MANUAL_ADJUSTMENT",
                oldState,
                newState
            );

        // Publish event to kafka-lite
        kafkaLiteBroker.send("product-events", new ProductEvent(id, "STOCK_ADJUSTED"));

        return mapToResponse(updatedProduct);
    }

    private ProductResponse mapToResponse(Product product) {
        return ProductResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .unitPrice(product.getUnitPrice())
                .stockQuantity(product.getStockQuantity())
                .reorderLevel(product.getReorderLevel())
                .warehouseId(product.getWarehouse() != null ? product.getWarehouse().getId() : null)
                .warehouseName(product.getWarehouse() != null ? product.getWarehouse().getName() : null)
                .lowStockIndicator(product.isLowStockIndicator())
                .isActive(product.isActive())
                .build();
    }

    private Map<String, Object> mapToAuditState(Product product) {
        Map<String, Object> state = new HashMap<>();
        state.put("sku", product.getSku());
        state.put("name", product.getName());
        state.put("stockQuantity", product.getStockQuantity());
        state.put("reorderLevel", product.getReorderLevel());
        state.put("unitPrice", product.getUnitPrice());
        return state;
    }
}

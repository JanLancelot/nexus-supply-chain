package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.ProductAdjustRequest;
import com.nexus.supplychain.dto.ProductCreateRequest;
import com.nexus.supplychain.dto.ProductResponse;
import com.nexus.supplychain.exception.BadRequestException;
import com.nexus.supplychain.exception.ResourceNotFoundException;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.ProductEvent;
import com.nexus.supplychain.model.Category;
import com.nexus.supplychain.model.Product;
import com.nexus.supplychain.model.Warehouse;
import com.nexus.supplychain.repository.CategoryRepository;
import com.nexus.supplychain.repository.ProductRepository;
import com.nexus.supplychain.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import com.nexus.supplychain.dto.PagedResponse;

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

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "'all'", sync = true)
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "#page + '-' + #size", sync = true)
    public PagedResponse<ProductResponse> getAllProducts(int page, int size) {
        return getAllProducts(page, size, null, null, null);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "products", key = "{#page, #size, #search, #warehouseId, #active}", sync = true)
    public PagedResponse<ProductResponse> getAllProducts(int page, int size, String search, UUID warehouseId, Boolean active) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id"));
        String normalizedSearch = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Slice<Product> productSlice;
        if (normalizedSearch.isEmpty() && warehouseId == null && active == null) {
            productSlice = productRepository.findSliceBy(pageable);
        } else {
            String pattern = normalizedSearch.isEmpty() ? "" : "%" + normalizedSearch
                    .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            productSlice = productRepository.searchProducts(pattern, warehouseId, active, pageable);
        }
        List<ProductResponse> content = productSlice.getContent().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
        return PagedResponse.<ProductResponse>builder()
                .content(content)
                .totalElements(-1L)
                .totalPages(-1)
                .pageNumber(productSlice.getNumber())
                .pageSize(productSlice.getSize())
                .hasNext(productSlice.hasNext())
                .build();
    }

    @Transactional
    public ProductResponse createProduct(ProductCreateRequest request) {
        if (request == null || request.getWarehouseId() == null) {
            throw new BadRequestException("Warehouse is required");
        }
        if (productRepository.findBySku(request.getSku()).isPresent()) {
            throw new BadRequestException("SKU already exists: " + request.getSku());
        }

        Category category = null;
        if (request.getCategoryId() != null) {
            category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + request.getCategoryId()));
        }

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found with ID: " + request.getWarehouseId()));

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
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with ID: " + id));

        if (request == null || request.getQuantityAdjustment() == null || request.getReasonCode() == null) {
            throw new BadRequestException("Quantity adjustment and reason code are required");
        }
        if (!ALLOWED_REASON_CODES.contains(request.getReasonCode())) {
            throw new BadRequestException("Invalid reason code: " + request.getReasonCode());
        }

        int oldStock = product.getStockQuantity();
        long adjustedStock = (long) oldStock + request.getQuantityAdjustment();

        if (adjustedStock < 0 || adjustedStock > Integer.MAX_VALUE) {
            throw new BadRequestException("Adjustment would put stock quantity outside the supported range");
        }
        int newStock = (int) adjustedStock;

        Map<String, Object> oldState = new HashMap<>();
        oldState.put("stockQuantity", oldStock);

        product.setStockQuantity(newStock);
        Product updatedProduct = productRepository.save(product);

        Map<String, Object> newState = new HashMap<>();
        newState.put("stockQuantity", newStock);
        newState.put("reasonCode", request.getReasonCode());
        newState.put("quantityAdjustment", request.getQuantityAdjustment());

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

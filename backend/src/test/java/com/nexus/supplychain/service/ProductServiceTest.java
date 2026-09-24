package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.ProductAdjustRequest;
import com.nexus.supplychain.dto.ProductCreateRequest;
import com.nexus.supplychain.dto.ProductResponse;
import com.nexus.supplychain.dto.PagedResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private KafkaLiteBroker kafkaLiteBroker;

    @InjectMocks
    private ProductService productService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetAllProducts() {
        Product p = Product.builder().id(UUID.randomUUID()).sku("SKU-123").unitPrice(BigDecimal.TEN).build();
        when(productRepository.findAll()).thenReturn(Collections.singletonList(p));

        List<ProductResponse> responses = productService.getAllProducts();
        assertEquals(1, responses.size());
        assertEquals("SKU-123", responses.get(0).getSku());
    }

    @Test
    void testGetAllProductsPaged() {
        Product p = Product.builder().id(UUID.randomUUID()).sku("SKU-123").unitPrice(BigDecimal.TEN).build();
        Slice<Product> slice = new SliceImpl<>(Collections.singletonList(p));
        when(productRepository.findSliceBy(any(Pageable.class))).thenReturn(slice);

        PagedResponse<ProductResponse> response = productService.getAllProducts(0, 10);
        assertEquals(1, response.getContent().size());
        assertEquals("SKU-123", response.getContent().get(0).getSku());
    }

    @Test
    void testCreateProduct_Success() {
        UUID categoryId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        ProductCreateRequest request = ProductCreateRequest.builder()
                .sku("SKU-123")
                .name("Product A")
                .categoryId(categoryId)
                .warehouseId(warehouseId)
                .unitPrice(BigDecimal.TEN)
                .reorderLevel(5)
                .isActive(true)
                .build();

        Category category = Category.builder().id(categoryId).name("Electronics").build();
        Warehouse warehouse = Warehouse.builder().id(warehouseId).name("Main").build();

        when(productRepository.findBySku("SKU-123")).thenReturn(Optional.empty());
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(warehouseRepository.findById(warehouseId)).thenReturn(Optional.of(warehouse));
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        ProductResponse response = productService.createProduct(request);
        assertNotNull(response);
        assertEquals("SKU-123", response.getSku());
        assertEquals("Electronics", response.getCategoryName());
        assertEquals("Main", response.getWarehouseName());

        verify(auditService, times(1)).logChange(eq("Product"), any(), eq("ACTION_CREATE_PRODUCT"), any(), any());
        verify(kafkaLiteBroker, times(1)).send(eq("product-events"), any(ProductEvent.class));
    }

    @Test
    void testCreateProduct_DuplicateSku() {
        ProductCreateRequest request = ProductCreateRequest.builder().sku("SKU-123").warehouseId(UUID.randomUUID()).build();
        when(productRepository.findBySku("SKU-123")).thenReturn(Optional.of(new Product()));

        assertThrows(BadRequestException.class, () -> productService.createProduct(request));
    }

    @Test
    void creatingAProductRequiresItsStockWarehouseBeforePersistence() {
        ProductCreateRequest request = ProductCreateRequest.builder().sku("NO-WAREHOUSE")
                .name("Unassigned").unitPrice(BigDecimal.ONE).reorderLevel(0).build();
        assertEquals("Warehouse is required", assertThrows(BadRequestException.class,
                () -> productService.createProduct(request)).getMessage());
        verifyNoInteractions(productRepository, warehouseRepository, auditService, kafkaLiteBroker);
    }

    @Test
    void testAdjustProductStock_Success() {
        UUID id = UUID.randomUUID();
        Product product = Product.builder()
                .id(id)
                .sku("SKU-123")
                .stockQuantity(10)
                .unitPrice(BigDecimal.TEN)
                .build();

        ProductAdjustRequest request = new ProductAdjustRequest(5, "CYCLIC_COUNT_DISCREPANCY");

        when(productRepository.findByIdForUpdate(id)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        ProductResponse response = productService.adjustProductStock(id, request);
        assertNotNull(response);
        assertEquals(15, response.getStockQuantity());

        verify(auditService, times(1)).logChange(eq("Product"), eq(id), eq("ACTION_MANUAL_ADJUSTMENT"),
                eq(java.util.Map.of("stockQuantity", 10)),
                eq(java.util.Map.of("stockQuantity", 15, "reasonCode", "CYCLIC_COUNT_DISCREPANCY", "quantityAdjustment", 5)));
        verify(kafkaLiteBroker, times(1)).send(eq("product-events"), any(ProductEvent.class));
    }

    @Test
    void testAdjustProductStock_InvalidReason() {
        UUID id = UUID.randomUUID();
        Product product = Product.builder().id(id).stockQuantity(10).build();
        ProductAdjustRequest request = new ProductAdjustRequest(5, "INVALID_REASON");

        when(productRepository.findByIdForUpdate(id)).thenReturn(Optional.of(product));

        assertThrows(BadRequestException.class, () -> productService.adjustProductStock(id, request));
    }

    @Test
    void testAdjustProductStock_NegativeStock() {
        UUID id = UUID.randomUUID();
        Product product = Product.builder().id(id).stockQuantity(10).build();
        ProductAdjustRequest request = new ProductAdjustRequest(-15, "CYCLIC_COUNT_DISCREPANCY");

        when(productRepository.findByIdForUpdate(id)).thenReturn(Optional.of(product));

        assertThrows(BadRequestException.class, () -> productService.adjustProductStock(id, request));
    }

    @Test
    void testAdjustProductStock_RejectsIntegerOverflow() {
        UUID id = UUID.randomUUID();
        Product product = Product.builder().id(id).stockQuantity(Integer.MAX_VALUE).build();
        when(productRepository.findByIdForUpdate(id)).thenReturn(Optional.of(product));

        assertThrows(BadRequestException.class, () -> productService.adjustProductStock(id,
                new ProductAdjustRequest(1, "CYCLIC_COUNT_DISCREPANCY")));

        assertEquals(Integer.MAX_VALUE, product.getStockQuantity());
        verify(productRepository, never()).save(any());
        verifyNoInteractions(auditService, kafkaLiteBroker);
    }
}

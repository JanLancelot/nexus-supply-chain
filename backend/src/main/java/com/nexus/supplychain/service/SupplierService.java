package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.SupplierCreateRequest;
import com.nexus.supplychain.dto.SupplierProductsRequest;
import com.nexus.supplychain.dto.SupplierResponse;
import com.nexus.supplychain.exception.BadRequestException;
import com.nexus.supplychain.exception.ResourceNotFoundException;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.kafkalite.event.ProductEvent;
import com.nexus.supplychain.model.Product;
import com.nexus.supplychain.model.Supplier;
import com.nexus.supplychain.repository.ProductRepository;
import com.nexus.supplychain.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SupplierService {
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final KafkaLiteBroker broker;

    @Transactional(readOnly = true)
    public List<SupplierResponse> getAllSuppliers() {
        return supplierRepository.findAll(Sort.by("name", "id")).stream().map(this::mapToResponse).toList();
    }

    @Transactional
    public SupplierResponse createSupplier(SupplierCreateRequest request) {
        String name = request.getName().trim();
        if (supplierRepository.findByName(name).isPresent()) {
            throw new BadRequestException("Supplier name already exists: " + name);
        }
        List<Product> products = resolveProducts(request.getProductIds());
        Supplier supplier = supplierRepository.saveAndFlush(Supplier.builder()
                .name(name).contactPerson(request.getContactPerson()).email(request.getEmail())
                .phone(request.getPhone()).address(request.getAddress())
                .leadTimeDays(request.getLeadTimeDays()).isActive(request.isActive()).build());
        replaceProducts(supplier.getId(), products, List.of());
        SupplierResponse response = mapToResponse(supplier);
        auditService.logChange("Supplier", supplier.getId(), "ACTION_CREATE_SUPPLIER", null, response);
        publishSourcingChanges(response.getProductIds());
        return response;
    }

    @Transactional(readOnly = true)
    public SupplierProductsRequest getProducts(UUID id) {
        if (!supplierRepository.existsById(id)) {
            throw new ResourceNotFoundException("Supplier not found with ID: " + id);
        }
        return new SupplierProductsRequest(supplierRepository.findProductIds(id));
    }

    @Transactional
    public SupplierProductsRequest updateProducts(UUID id, SupplierProductsRequest request) {
        Supplier supplier = supplierRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found with ID: " + id));
        List<Product> products = resolveProducts(request.getProductIds());
        List<UUID> previous = supplierRepository.findProductIds(id);
        replaceProducts(supplier.getId(), products, previous);
        SupplierProductsRequest response = new SupplierProductsRequest(supplierRepository.findProductIds(id));
        auditService.logChange("Supplier", id, "ACTION_UPDATE_SUPPLIER_PRODUCTS",
                Map.of("productIds", previous), response);
        HashSet<UUID> affected = new HashSet<>(previous);
        affected.addAll(response.getProductIds());
        publishSourcingChanges(affected);
        return response;
    }

    private List<Product> resolveProducts(List<UUID> productIds) {
        if (productIds == null || productIds.size() > 500 || productIds.stream().anyMatch(id -> id == null)
                || new HashSet<>(productIds).size() != productIds.size()) {
            throw new BadRequestException("Supply between 0 and 500 distinct product IDs");
        }
        List<Product> products = productRepository.findAllById(productIds);
        if (products.size() != productIds.size()) {
            throw new ResourceNotFoundException("One or more products do not exist");
        }
        return products;
    }

    private void publishSourcingChanges(Iterable<UUID> productIds) {
        for (UUID productId : productIds) {
            broker.send("product-events", new ProductEvent(productId, "SOURCING_UPDATED"));
        }
    }

    private void replaceProducts(UUID supplierId, List<Product> products, List<UUID> previous) {
        HashSet<UUID> retained = new HashSet<>();
        products.forEach(product -> retained.add(product.getId()));
        for (UUID productId : previous) {
            if (!retained.contains(productId)) {
                supplierRepository.deleteProductAssociation(supplierId, productId);
            }
        }
        HashSet<UUID> existing = new HashSet<>(previous);
        for (Product product : products) {
            if (!existing.contains(product.getId())) {
                supplierRepository.addProductAssociation(supplierId, product.getId(), product.getUnitPrice());
            }
        }
    }

    private SupplierResponse mapToResponse(Supplier supplier) {
        return SupplierResponse.builder().id(supplier.getId()).name(supplier.getName())
                .contactPerson(supplier.getContactPerson()).email(supplier.getEmail())
                .phone(supplier.getPhone()).address(supplier.getAddress()).active(supplier.isActive())
                .leadTimeDays(supplier.getLeadTimeDays()).createdAt(supplier.getCreatedAt())
                .productIds(supplierRepository.findProductIds(supplier.getId())).build();
    }
}

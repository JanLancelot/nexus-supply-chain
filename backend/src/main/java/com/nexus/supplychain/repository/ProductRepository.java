package com.nexus.supplychain.repository;

import com.nexus.supplychain.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Override
    @EntityGraph(attributePaths = {"category", "warehouse"})
    List<Product> findAll();

    @EntityGraph(attributePaths = {"category", "warehouse"})
    Slice<Product> findSliceBy(Pageable pageable);

    @EntityGraph(attributePaths = {"category", "warehouse"})
    @Query("SELECT p FROM Product p WHERE (:search = '' OR LOWER(p.name) LIKE :search ESCAPE '!' "
            + "OR LOWER(p.sku) LIKE :search ESCAPE '!') "
            + "AND (:warehouseId IS NULL OR p.warehouse.id = :warehouseId) "
            + "AND (:active IS NULL OR p.isActive = :active)")
    Slice<Product> searchProducts(String search, UUID warehouseId, Boolean active, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"category", "warehouse"})
    Optional<Product> findById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(UUID id);
    Optional<Product> findBySku(String sku);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.stockQuantity < p.reorderLevel AND p.isActive = true")
    long countLowStockProducts();

    @Query("SELECT COALESCE(SUM(p.unitPrice * p.stockQuantity), 0) FROM Product p WHERE p.isActive = true")
    BigDecimal calculateTotalInventoryValue();

    @Query("SELECT w.id, w.name, w.location, COALESCE(SUM(p.stockQuantity), 0) FROM Product p JOIN p.warehouse w GROUP BY w.id, w.name, w.location ORDER BY w.name, w.id")
    List<Object[]> countStockByWarehouse();
}

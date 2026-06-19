package com.pg.supplychain.repository;

import com.pg.supplychain.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    @Override
    @EntityGraph(attributePaths = {"category", "warehouse"})
    List<Product> findAll();

    @Override
    @EntityGraph(attributePaths = {"category", "warehouse"})
    Page<Product> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"category", "warehouse"})
    Optional<Product> findById(UUID id);
    Optional<Product> findBySku(String sku);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.stockQuantity < p.reorderLevel AND p.isActive = true")
    long countLowStockProducts();

    @Query("SELECT COALESCE(SUM(p.unitPrice * p.stockQuantity), 0) FROM Product p WHERE p.isActive = true")
    BigDecimal calculateTotalInventoryValue();

    @Query("SELECT w.name, COALESCE(SUM(p.stockQuantity), 0) FROM Product p JOIN p.warehouse w GROUP BY w.name")
    List<Object[]> countStockByWarehouse();
}

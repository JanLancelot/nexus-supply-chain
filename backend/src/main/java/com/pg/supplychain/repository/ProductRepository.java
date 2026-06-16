package com.pg.supplychain.repository;

import com.pg.supplychain.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    Optional<Product> findBySku(String sku);

    @Query("SELECT COUNT(p) FROM Product p WHERE p.stockQuantity < p.reorderLevel AND p.isActive = true")
    long countLowStockProducts();

    @Query("SELECT COALESCE(SUM(p.unitPrice * p.stockQuantity), 0) FROM Product p WHERE p.isActive = true")
    BigDecimal calculateTotalInventoryValue();

    @Query("SELECT w.name, COALESCE(SUM(p.stockQuantity), 0) FROM Product p JOIN p.warehouse w GROUP BY w.name")
    List<Object[]> countStockByWarehouse();

    @Query("SELECT p FROM Product p LEFT JOIN FETCH p.category LEFT JOIN FETCH p.warehouse")
    List<Product> findAllWithCategoryAndWarehouse();

    @Query(value = "SELECT p FROM Product p LEFT JOIN FETCH p.category LEFT JOIN FETCH p.warehouse",
           countQuery = "SELECT COUNT(p) FROM Product p")
    Page<Product> findAllWithCategoryAndWarehouse(Pageable pageable);
}

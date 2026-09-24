package com.nexus.supplychain.repository;

import com.nexus.supplychain.model.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    Optional<Supplier> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Supplier s WHERE s.id = :id")
    Optional<Supplier> findByIdForUpdate(UUID id);

    @Query(value = "SELECT CAST(product_id AS varchar(36)) FROM supplier_products WHERE supplier_id = :supplierId ORDER BY product_id", nativeQuery = true)
    List<UUID> findProductIds(UUID supplierId);

    @Modifying
    @Query(value = "DELETE FROM supplier_products WHERE supplier_id = :supplierId AND product_id = :productId", nativeQuery = true)
    void deleteProductAssociation(UUID supplierId, UUID productId);

    @Modifying
    @Query(value = "INSERT INTO supplier_products (supplier_id, product_id, supply_price) VALUES (:supplierId, :productId, :supplyPrice)", nativeQuery = true)
    void addProductAssociation(UUID supplierId, UUID productId, BigDecimal supplyPrice);

    @Query(value =
        "SELECT s.* FROM suppliers s " +
        "JOIN supplier_products sp ON s.id = sp.supplier_id " +
        "WHERE sp.product_id = :productId AND s.is_active = true " +
        "ORDER BY s.lead_time_days ASC NULLS LAST, s.id ASC LIMIT 1", nativeQuery = true)
    Optional<Supplier> findPreferredSupplierForProduct(java.util.UUID productId);
}

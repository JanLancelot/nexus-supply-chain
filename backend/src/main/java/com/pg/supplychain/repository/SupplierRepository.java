package com.pg.supplychain.repository;

import com.pg.supplychain.model.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    Optional<Supplier> findByName(String name);

    @org.springframework.data.jpa.repository.Query(value = 
        "SELECT s.* FROM suppliers s " +
        "JOIN supplier_products sp ON s.id = sp.supplier_id " +
        "WHERE sp.product_id = :productId AND s.is_active = true " +
        "LIMIT 1", nativeQuery = true)
    Optional<Supplier> findPreferredSupplierForProduct(java.util.UUID productId);
}

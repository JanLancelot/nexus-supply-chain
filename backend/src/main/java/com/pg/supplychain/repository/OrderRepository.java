package com.pg.supplychain.repository;

import com.pg.supplychain.model.Order;
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
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Override
    @EntityGraph(attributePaths = {"supplier", "warehouse", "createdBy"})
    List<Order> findAll();

    @Override
    @EntityGraph(attributePaths = {"supplier", "warehouse", "createdBy"})
    Page<Order> findAll(Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"supplier", "warehouse", "createdBy"})
    Optional<Order> findById(UUID id);

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.status = com.pg.supplychain.model.OrderStatus.DELIVERED")
    BigDecimal calculateTotalRevenue();

    @Query("SELECT o.status, COUNT(o) FROM Order o GROUP BY o.status")
    List<Object[]> countOrdersByStatus();

    @Query("SELECT p.name, COALESCE(SUM(oi.quantity), 0) as totalQty FROM OrderItem oi JOIN oi.product p GROUP BY p.name ORDER BY totalQty DESC")
    List<Object[]> findTopOrderedProducts(Pageable pageable);

    @Query("SELECT COUNT(o) FROM Order o JOIN o.items oi WHERE oi.product.id = :productId AND o.status NOT IN (com.pg.supplychain.model.OrderStatus.DELIVERED, com.pg.supplychain.model.OrderStatus.CANCELLED)")
    long countOpenOrdersForProduct(UUID productId);
}

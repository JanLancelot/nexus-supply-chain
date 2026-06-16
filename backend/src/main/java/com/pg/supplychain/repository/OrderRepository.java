package com.pg.supplychain.repository;

import com.pg.supplychain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.status = com.pg.supplychain.model.OrderStatus.DELIVERED")
    BigDecimal calculateTotalRevenue();

    @Query("SELECT o.status, COUNT(o) FROM Order o GROUP BY o.status")
    List<Object[]> countOrdersByStatus();

    @Query("SELECT p.name, COALESCE(SUM(oi.quantity), 0) as totalQty FROM OrderItem oi JOIN oi.product p GROUP BY p.name ORDER BY totalQty DESC")
    List<Object[]> findTopOrderedProducts(Pageable pageable);

    @Query("SELECT COUNT(o) FROM Order o JOIN o.items oi WHERE oi.product.id = :productId AND o.status NOT IN (com.pg.supplychain.model.OrderStatus.DELIVERED, com.pg.supplychain.model.OrderStatus.CANCELLED)")
    long countOpenOrdersForProduct(UUID productId);

    @Query(value = "SELECT o FROM Order o LEFT JOIN FETCH o.supplier LEFT JOIN FETCH o.warehouse",
           countQuery = "SELECT COUNT(o) FROM Order o")
    Page<Order> findAllWithDetails(Pageable pageable);
}

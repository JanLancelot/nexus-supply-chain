package com.pg.supplychain.repository;

import com.pg.supplychain.model.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    @Query("SELECT w FROM Warehouse w LEFT JOIN FETCH w.manager")
    List<Warehouse> findAllWithManager();

    Optional<Warehouse> findFirstByOrderByIdAsc();
}

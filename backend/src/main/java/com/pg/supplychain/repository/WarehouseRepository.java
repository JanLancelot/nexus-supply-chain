package com.pg.supplychain.repository;

import com.pg.supplychain.model.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.EntityGraph;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    @Override
    @EntityGraph(attributePaths = {"manager"})
    List<Warehouse> findAll();

    @Override
    @EntityGraph(attributePaths = {"manager"})
    Optional<Warehouse> findById(UUID id);
}

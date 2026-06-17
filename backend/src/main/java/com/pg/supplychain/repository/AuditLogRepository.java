package com.pg.supplychain.repository;

import com.pg.supplychain.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Override
    @EntityGraph(attributePaths = {"user"})
    Page<AuditLog> findAll(Pageable pageable);
}

package com.pg.supplychain.repository;

import com.pg.supplychain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    @Override
    @Cacheable(value = "users", key = "#id")
    Optional<User> findById(UUID id);

    @Cacheable(value = "users", key = "'role-' + #roleName")
    @Query("SELECT u FROM User u JOIN FETCH u.role WHERE u.role.name = :roleName")
    List<User> findByRoleName(@Param("roleName") String roleName);
}

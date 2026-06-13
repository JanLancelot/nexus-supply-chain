package com.pg.supplychain.repository;

import com.pg.supplychain.model.Notification;
import com.pg.supplychain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    
    List<Notification> findByUserOrderByCreatedAtDesc(User user);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.user = :user AND n.isRead = false")
    void markAllAsReadForUser(@Param("user") User user);
}

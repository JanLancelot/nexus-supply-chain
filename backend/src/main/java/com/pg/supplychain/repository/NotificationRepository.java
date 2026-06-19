package com.pg.supplychain.repository;

import com.pg.supplychain.model.Notification;
import com.pg.supplychain.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    
    @EntityGraph(attributePaths = {"user"})
    List<Notification> findTop100ByUserOrderByCreatedAtDesc(User user);

    @EntityGraph(attributePaths = {"user"})
    Page<Notification> findByUser(User user, Pageable pageable);

    @EntityGraph(attributePaths = {"user"})
    List<Notification> findListByUser(User user, Pageable pageable);

    long countByUserAndIsReadFalse(User user);

    @Query("SELECT COUNT(n), SUM(CASE WHEN n.isRead = false THEN 1 ELSE 0 END) FROM Notification n WHERE n.user = :user")
    List<Object[]> countTotalAndUnreadByUser(@Param("user") User user);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.user = :user AND n.isRead = false")
    void markAllAsReadForUser(@Param("user") User user);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :threshold")
    int deleteOldNotifications(@Param("threshold") OffsetDateTime threshold);
}

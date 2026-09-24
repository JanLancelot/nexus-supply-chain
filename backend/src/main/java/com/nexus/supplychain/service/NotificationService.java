package com.nexus.supplychain.service;

import com.nexus.supplychain.dto.NotificationListResponse;
import com.nexus.supplychain.dto.NotificationResponse;
import com.nexus.supplychain.exception.ResourceNotFoundException;
import com.nexus.supplychain.model.Notification;
import com.nexus.supplychain.model.User;
import com.nexus.supplychain.repository.NotificationRepository;
import com.nexus.supplychain.security.SecurityContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SecurityContextService securityContextService;

    @Value("${app.notifications.read-retention-days:30}")
    private int readRetentionDays = 30;

    @Transactional(readOnly = true)
    public NotificationListResponse getNotificationsForCurrentUser() {
        User currentUser = securityContextService.getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        List<Notification> notifications = notificationRepository.findListByUser(
                currentUser,
                PageRequest.of(0, 50, Sort.by("createdAt").descending())
        );

        List<NotificationResponse> list = notifications.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        long totalCount = 0;
        long unreadCount = 0;

        List<Object[]> counts = notificationRepository.countTotalAndUnreadByUser(currentUser);
        if (counts != null && !counts.isEmpty()) {
            Object[] row = counts.get(0);
            totalCount = row[0] != null ? ((Number) row[0]).longValue() : 0L;
            unreadCount = row[1] != null ? ((Number) row[1]).longValue() : 0L;
        }

        return NotificationListResponse.builder()
                .notifications(list)
                .totalCount(totalCount)
                .unreadCount(unreadCount)
                .build();
    }

    @Transactional
    public void markAsRead(UUID notificationId) {
        User currentUser = securityContextService.getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found with ID: " + notificationId));

        if (!notification.getUser().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("You do not have permission to modify this notification");
        }

        notification.setRead(true);
        notificationRepository.save(notification);
        log.debug("NotificationService: Marked notification {} as read for user {}", notificationId, currentUser.getEmail());
    }

    @Transactional
    public void markAllAsRead() {
        User currentUser = securityContextService.getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        notificationRepository.markAllAsReadForUser(currentUser);
        log.debug("NotificationService: Marked all notifications as read for user {}", currentUser.getEmail());
    }

    @Transactional
    public Notification createNotification(User user, String type, String message) {
        return createNotificationForEvent(user, type, message, null);
    }

    @Transactional
    public Notification createNotificationForEvent(User user, String type, String message, UUID eventId) {
        if (eventId != null) {
            var existing = notificationRepository.findByUserAndSourceEventId(user, eventId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        Notification notification = Notification.builder()
                .sourceEventId(eventId)
                .user(user)
                .type(type)
                .message(message)
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();
        Notification saved = notificationRepository.save(notification);
        log.debug("NotificationService: Created notification for user {}, type={}", user.getEmail(), type);
        return saved;
    }

    @Scheduled(cron = "0 0 * * * *") // Runs every hour
    @Transactional
    public void pruneOldNotifications() {
        if (readRetentionDays <= 0) {
            return;
        }
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(readRetentionDays);
        int deleted = notificationRepository.deleteOldReadNotifications(threshold);
        log.info("NotificationService: Pruned {} read notifications older than {} days.", deleted, readRetentionDays);
    }

    private NotificationResponse mapToResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .userId(notification.getUser().getId())
                .type(notification.getType())
                .message(notification.getMessage())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}

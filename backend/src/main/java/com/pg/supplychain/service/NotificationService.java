package com.pg.supplychain.service;

import com.pg.supplychain.dto.NotificationListResponse;
import com.pg.supplychain.dto.NotificationResponse;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.model.Notification;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.NotificationRepository;
import com.pg.supplychain.security.SecurityContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
        Notification notification = Notification.builder()
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
        OffsetDateTime threshold = OffsetDateTime.now().minusHours(24);
        int deleted = notificationRepository.deleteOldNotifications(threshold);
        log.info("NotificationService: Pruned {} notifications older than 24 hours.", deleted);
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

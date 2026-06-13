package com.pg.supplychain.service;

import com.pg.supplychain.dto.NotificationResponse;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.model.Notification;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.NotificationRepository;
import com.pg.supplychain.security.SecurityContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    public List<NotificationResponse> getNotificationsForCurrentUser() {
        User currentUser = securityContextService.getCurrentUser();
        if (currentUser == null) {
            throw new AccessDeniedException("User is not authenticated");
        }

        List<Notification> notifications = notificationRepository.findByUserOrderByCreatedAtDesc(currentUser);
        return notifications.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
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

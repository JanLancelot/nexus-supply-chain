package com.pg.supplychain.service;

import com.pg.supplychain.dto.NotificationListResponse;
import com.pg.supplychain.exception.ResourceNotFoundException;
import com.pg.supplychain.model.Notification;
import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.NotificationRepository;
import com.pg.supplychain.security.SecurityContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private SecurityContextService securityContextService;

    @InjectMocks
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testGetNotificationsForCurrentUser_Unauthenticated() {
        when(securityContextService.getCurrentUser()).thenReturn(null);
        assertThrows(AccessDeniedException.class, () -> notificationService.getNotificationsForCurrentUser());
    }

    @Test
    void testGetNotificationsForCurrentUser_Success() {
        User user = User.builder().id(UUID.randomUUID()).email("user@pg.com").build();
        Notification n = Notification.builder()
                .id(UUID.randomUUID())
                .user(user)
                .type("TYPE")
                .message("Msg")
                .isRead(false)
                .build();

        when(securityContextService.getCurrentUser()).thenReturn(user);
        when(notificationRepository.findListByUser(eq(user), any())).thenReturn(Collections.singletonList(n));

        NotificationListResponse response = notificationService.getNotificationsForCurrentUser();
        assertNotNull(response);
        assertEquals(1, response.getNotifications().size());
        assertEquals("Msg", response.getNotifications().get(0).getMessage());
    }

    @Test
    void testMarkAsRead_Success() {
        User user = User.builder().id(UUID.randomUUID()).email("user@pg.com").build();
        Notification n = Notification.builder().id(UUID.randomUUID()).user(user).isRead(false).build();

        when(securityContextService.getCurrentUser()).thenReturn(user);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        notificationService.markAsRead(n.getId());

        assertTrue(n.isRead());
        verify(notificationRepository, times(1)).save(n);
    }

    @Test
    void testMarkAsRead_Unauthorized() {
        User user = User.builder().id(UUID.randomUUID()).build();
        User otherUser = User.builder().id(UUID.randomUUID()).build();
        Notification n = Notification.builder().id(UUID.randomUUID()).user(otherUser).isRead(false).build();

        when(securityContextService.getCurrentUser()).thenReturn(user);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThrows(AccessDeniedException.class, () -> notificationService.markAsRead(n.getId()));
    }

    @Test
    void testMarkAllAsRead() {
        User user = User.builder().id(UUID.randomUUID()).email("user@pg.com").build();
        when(securityContextService.getCurrentUser()).thenReturn(user);

        notificationService.markAllAsRead();

        verify(notificationRepository, times(1)).markAllAsReadForUser(user);
    }

    @Test
    void testCreateNotification() {
        User user = User.builder().id(UUID.randomUUID()).email("user@pg.com").build();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        Notification n = notificationService.createNotification(user, "ALERT", "Attention");
        assertNotNull(n);
        assertEquals("ALERT", n.getType());
        assertEquals("Attention", n.getMessage());
        assertFalse(n.isRead());
    }

    @Test
    void testPruneOldNotifications() {
        notificationService.pruneOldNotifications();
        verify(notificationRepository, times(1)).deleteOldNotifications(any(OffsetDateTime.class));
    }
}

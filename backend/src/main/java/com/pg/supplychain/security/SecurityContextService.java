package com.pg.supplychain.security;

import com.pg.supplychain.model.User;
import com.pg.supplychain.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SecurityContextService {

    private final UserService userService;

    /**
     * Retrieves the currently authenticated User from the SecurityContext.
     *
     * @return User object or null if not authenticated or not found.
     */
    public User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Map) {
            Map<?, ?> principal = (Map<?, ?>) auth.getPrincipal();
            String userIdStr = (String) principal.get("userId");
            if (userIdStr != null) {
                try {
                    return userService.getUserById(UUID.fromString(userIdStr));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}

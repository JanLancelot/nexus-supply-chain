package com.pg.supplychain.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private final int maxAttempts;
    private final int maxClients;
    private final Clock clock;
    private final Map<String, Window> clients = new HashMap<>();

    @Autowired
    public LoginRateLimitFilter(
            @Value("${app.login.max-attempts-per-minute:30}") int maxAttempts,
            @Value("${app.login.max-tracked-clients:10000}") int maxClients) {
        this(maxAttempts, maxClients, Clock.systemUTC());
    }

    LoginRateLimitFilter(int maxAttempts, int maxClients, Clock clock) {
        if (maxAttempts < 1 || maxClients < 1) {
            throw new IllegalArgumentException("Login rate-limit settings must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.maxClients = maxClients;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8)
                    .equals(request.getContextPath() + "/api/v1/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Use the socket peer address, never caller-supplied forwarding headers.
        long retryAfterSeconds = reserveAttempt(request.getRemoteAddr());
        if (retryAfterSeconds > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.setHeader("Cache-Control", "no-store");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Too many login attempts. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized long reserveAttempt(String address) {
        long now = clock.millis();
        Window window = clients.get(address);
        if (window != null && window.resetAt <= now) {
            clients.remove(address);
            window = null;
        }
        if (window == null) {
            if (clients.size() >= maxClients) {
                clients.values().removeIf(candidate -> candidate.resetAt <= now);
                if (clients.size() >= maxClients) {
                    // Do not evict active limits: rotating addresses must not reset another client's counter.
                    long firstExpiry = clients.values().stream().mapToLong(candidate -> candidate.resetAt).min().orElse(now + WINDOW_MILLIS);
                    return secondsUntil(firstExpiry, now);
                }
            }
            window = new Window(now + WINDOW_MILLIS);
            clients.put(address, window);
        }
        if (window.attempts >= maxAttempts) {
            return secondsUntil(window.resetAt, now);
        }
        window.attempts++;
        return 0;
    }

    private long secondsUntil(long timestamp, long now) {
        return Math.max(1, (timestamp - now + 999) / 1000);
    }

    private static final class Window {
        private final long resetAt;
        private int attempts;

        private Window(long resetAt) {
            this.resetAt = resetAt;
        }
    }
}

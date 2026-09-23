package com.pg.supplychain.security;

import com.pg.supplychain.model.User;
import com.pg.supplychain.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");
        final String jwt;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);
        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                Claims claims = jwtService.extractAllClaims(jwt);
                Date expiration = claims.getExpiration();
                String userId = claims.get("userId", String.class);
                if (expiration != null && expiration.after(new Date()) && userId != null) {
                    User user = userRepository.findById(UUID.fromString(userId)).orElse(null);
                    // Read current account state so disabling, deleting, or changing a role takes effect immediately.
                    if (user != null && "ACTIVE".equals(user.getStatus())
                            && user.getEmail().equals(claims.getSubject()) && user.getRole() != null) {
                        String role = user.getRole().getName();
                        Map<String, Object> principal = Map.of(
                                "email", user.getEmail(), "role", role, "userId", userId);
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, Collections.singletonList(new SimpleGrantedAuthority(role)));
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            }
        } catch (JwtException | IllegalArgumentException e) {
            // Invalid bearer tokens remain unauthenticated; do not log attacker-controlled token details.
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}

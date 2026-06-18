package com.pg.supplychain.security;

import io.jsonwebtoken.Claims;
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
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

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
                if (claims != null) {
                    Date expiration = claims.getExpiration();
                    if (expiration != null && !expiration.before(new Date())) {
                        String userEmail = claims.getSubject();
                        String role = claims.get("role", String.class);
                        String userId = claims.get("userId", String.class);

                        if (userEmail != null && role != null && userId != null) {
                            // Reconstruct authorities from the JWT claim
                            SimpleGrantedAuthority authority = new SimpleGrantedAuthority(role);

                            // Custom principal details map to bypass DB lookup during filter authentication
                            Map<String, Object> principalDetails = new HashMap<>();
                            principalDetails.put("email", userEmail);
                            principalDetails.put("role", role);
                            principalDetails.put("userId", userId);

                            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                    principalDetails,
                                    null,
                                    Collections.singletonList(authority)
                            );

                            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authToken);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Cannot set user authentication: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}

package com.pg.supplychain.config;

import com.pg.supplychain.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                // Public endpoints
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                
                // Inventory Catalog rules
                .requestMatchers(HttpMethod.GET, "/api/v1/inventory/products").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/inventory/products").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/inventory/products/*/adjust").hasAuthority("ROLE_ADMIN")
                
                // Purchase Order rules
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/orders/*/status").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                
                // Notification rules
                .requestMatchers("/api/v1/notifications/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")

                // Analytics rules
                .requestMatchers("/api/v1/analytics/**").hasAuthority("ROLE_ADMIN")

                // Forensic Audit Log rules
                .requestMatchers(HttpMethod.GET, "/api/v1/audit-logs").hasAuthority("ROLE_ADMIN")
                
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

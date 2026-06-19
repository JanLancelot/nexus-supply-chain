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

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.FORWARD, jakarta.servlet.DispatcherType.ERROR).permitAll()
                // Public endpoints
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // Inventory Catalog rules
                .requestMatchers(HttpMethod.GET, "/api/v1/inventory/products").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/inventory/products").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/inventory/products/*/adjust").hasAuthority("ROLE_ADMIN")
                
                // Purchase Order rules
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/orders/*/status").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                
                // Supplier rules
                .requestMatchers(HttpMethod.GET, "/api/v1/suppliers").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                
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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(
            "http://localhost",
            "http://localhost:5173",
            "https://pg-enterprise-supply-ui-staging.azurewebsites.net",
            "https://pg-enterprise-supply-ui.azurewebsites.net"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

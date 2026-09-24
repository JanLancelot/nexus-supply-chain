package com.nexus.supplychain.config;

import com.nexus.supplychain.security.JwtAuthenticationFilter;
import com.nexus.supplychain.security.LoginRateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
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
    private final LoginRateLimitFilter loginRateLimitFilter;

    @Value("${management.server.port:9091}")
    private int managementPort;

    @Value("${server.port:8080}")
    private int applicationPort;

    @Value("${app.cors.allowed-origins:http://localhost,http://localhost:5173}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                    + "style-src-attr 'none'; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) -> response.sendError(401)))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.FORWARD, jakarta.servlet.DispatcherType.ERROR).permitAll()
                // Public endpoints
                .requestMatchers("/", "/index.html", "/favicon.ico", "/favicon.svg", "/assets/**", "/login", "/dashboard", "/catalog", "/orders", "/audit-logs", "/users", "/reference-data").permitAll()
                .requestMatchers("/api/health").permitAll()
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/actuator/prometheus").access((authentication, context) ->
                    new AuthorizationDecision(managementPort != applicationPort
                        && context.getRequest().getLocalPort() == managementPort))
                .requestMatchers("/actuator/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                
                // Inventory Catalog rules
                .requestMatchers(HttpMethod.GET, "/api/v1/inventory/products").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/inventory/products").hasAuthority("ROLE_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/inventory/products/*/adjust").hasAuthority("ROLE_ADMIN")
                
                .requestMatchers(HttpMethod.GET, "/api/v1/categories/**", "/api/v1/warehouses/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers("/api/v1/categories/**", "/api/v1/warehouses/**").hasAuthority("ROLE_ADMIN")

                // Purchase Order rules
                .requestMatchers(HttpMethod.POST, "/api/v1/orders").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/orders/*/status").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/orders/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                
                // Supplier rules
                .requestMatchers(HttpMethod.GET, "/api/v1/suppliers/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")
                .requestMatchers("/api/v1/suppliers/**").hasAuthority("ROLE_ADMIN")
                
                // Notification rules
                .requestMatchers("/api/v1/notifications/**").hasAnyAuthority("ROLE_STAFF", "ROLE_ADMIN")

                // Analytics rules
                .requestMatchers("/api/v1/analytics/**").hasAuthority("ROLE_ADMIN")

                // Forensic Audit Log rules
                .requestMatchers(HttpMethod.GET, "/api/v1/audit-logs").hasAuthority("ROLE_ADMIN")

                // User management rules
                .requestMatchers("/api/v1/users/**").hasAuthority("ROLE_ADMIN")
                
                .anyRequest().denyAll()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(loginRateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}

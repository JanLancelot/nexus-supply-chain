package com.pg.supplychain.config;

import com.pg.supplychain.repository.UserRepository;
import com.pg.supplychain.security.JwtAuthenticationFilter;
import com.pg.supplychain.security.JwtService;
import com.pg.supplychain.security.LoginRateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SecurityConfigTest.TestConfig.class)
class SecurityConfigTest {
    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void staffCanReadButCannotMutateCategoriesAndWarehouses() throws Exception {
        for (String path : new String[]{"/api/v1/categories", "/api/v1/warehouses"}) {
            mvc.perform(get(path).with(user("staff").roles("STAFF"))).andExpect(status().isOk());
            for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
                mvc.perform(request(method, path).with(user("staff").roles("STAFF")))
                        .andExpect(status().isForbidden());
                mvc.perform(request(method, path).with(user("admin").roles("ADMIN")))
                        .andExpect(status().isOk());
            }
        }
    }

    @Test
    void sendsBrowserSecurityHeadersAndAllowsTheSiteIcon() throws Exception {
        mvc.perform(get("/favicon.svg"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'self'")))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void anonymousUsersCannotReadPrivateData() throws Exception {
        mvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownApiRoutesAreDeniedEvenToAdministrators() throws Exception {
        mvc.perform(get("/api/v1/unconfigured").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void prometheusOnlyAllowsRequestsOnSeparateManagementListener() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(8080); return request; }))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(user("admin").roles("ADMIN"))
                .with(request -> { request.setLocalPort(8080); return request; }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(request -> { request.setLocalPort(9091); return request; }))
                .andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean
        JwtAuthenticationFilter jwtFilter() {
            return new JwtAuthenticationFilter(mock(JwtService.class), mock(UserRepository.class));
        }
        @Bean
        LoginRateLimitFilter loginLimiter() { return new LoginRateLimitFilter(30, 100); }
        @Bean
        TestEndpoints endpoints() { return new TestEndpoints(); }
    }

    @RestController
    static class TestEndpoints {
        @RequestMapping({"/api/v1/categories", "/api/v1/warehouses", "/api/v1/unconfigured", "/actuator/prometheus", "/favicon.svg"})
        String endpoint() { return "ok"; }
    }
}

package com.pg.supplychain.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginRateLimitFilterTest {
    private final Clock clock = mock(Clock.class);
    private LoginRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        when(clock.millis()).thenReturn(1_000L);
        filter = new LoginRateLimitFilter(2, 2, clock);
    }

    @Test
    void blocksExcessAttemptsAndAllowsThemWhenWindowExpires() throws Exception {
        assertEquals(200, login("192.0.2.1").getStatus());
        assertEquals(200, login("192.0.2.1").getStatus());
        var blocked = login("192.0.2.1");
        assertEquals(429, blocked.getStatus());
        assertEquals("60", blocked.getHeader("Retry-After"));
        when(clock.millis()).thenReturn(60_001L);
        assertEquals("1", login("192.0.2.1").getHeader("Retry-After"));
        when(clock.millis()).thenReturn(61_000L);
        assertEquals(200, login("192.0.2.1").getStatus());
    }

    @Test
    void clientAddressHeadersCannotResetTheLimit() throws Exception {
        login("192.0.2.1");
        login("192.0.2.1");
        var request = loginRequest("192.0.2.1");
        request.addHeader("X-Forwarded-For", "192.0.2.2");
        request.addHeader("Forwarded", "for=192.0.2.2");
        assertEquals(429, perform(request).getStatus());
        assertEquals(200, login("192.0.2.2").getStatus());
    }

    @Test
    void boundsTrackedClientsWithoutEvictingActiveLimits() throws Exception {
        login("192.0.2.1");
        login("192.0.2.1");
        login("192.0.2.2");
        assertEquals(429, login("192.0.2.3").getStatus());
        assertEquals(429, login("192.0.2.1").getStatus());
        when(clock.millis()).thenReturn(61_000L);
        assertEquals(200, login("192.0.2.3").getStatus());
    }

    @Test
    void encodedLoginPathsCannotBypassTheLimit() throws Exception {
        login("192.0.2.1");
        login("192.0.2.1");
        var request = loginRequest("192.0.2.1");
        request.setRequestURI("/api/v1/auth/%6cogin");
        assertEquals(429, perform(request).getStatus());
    }

    @Test
    void onlyLimitsPostRequestsToLoginIncludingContextPaths() throws Exception {
        login("192.0.2.1");
        login("192.0.2.1");
        var request = loginRequest("192.0.2.1");
        request.setMethod("OPTIONS");
        assertEquals(200, perform(request).getStatus());
        request = loginRequest("192.0.2.1");
        request.setRequestURI("/api/v1/inventory/products");
        assertEquals(200, perform(request).getStatus());
        request = loginRequest("192.0.2.1");
        request.setContextPath("/supply");
        request.setRequestURI("/supply/api/v1/auth/login");
        assertEquals(429, perform(request).getStatus());
    }

    private MockHttpServletResponse login(String address) throws Exception {
        return perform(loginRequest(address));
    }

    private MockHttpServletRequest loginRequest(String address) {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(address);
        return request;
    }

    private MockHttpServletResponse perform(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var proceeded = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> proceeded.set(true));
        assertEquals(response.getStatus() != 429, proceeded.get());
        return response;
    }
}

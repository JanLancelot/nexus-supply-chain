package com.pg.supplychain.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequestTracingFilterTest {

    private RequestTracingFilter filter;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        filter = new RequestTracingFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
        MDC.clear();
    }

    @Test
    void testFilter_GeneratesCorrelationIdWhenMissing() throws Exception {
        when(request.getHeader("X-Correlation-Id")).thenReturn(null);

        doAnswer(invocation -> {
            String correlationId = MDC.get("correlationId");
            assertNotNull(correlationId);
            assertFalse(correlationId.isBlank());
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(response, times(1)).setHeader(eq("X-Correlation-Id"), anyString());
        assertNull(MDC.get("correlationId")); // Cleared after execution
    }

    @Test
    void testFilter_UsesExistingCorrelationIdFromHeader() throws Exception {
        String existingId = "existing-correlation-id-999";
        when(request.getHeader("X-Correlation-Id")).thenReturn(existingId);

        doAnswer(invocation -> {
            String correlationId = MDC.get("correlationId");
            assertEquals(existingId, correlationId);
            return null;
        }).when(filterChain).doFilter(any(), any());

        filter.doFilterInternal(request, response, filterChain);

        verify(response, times(1)).setHeader("X-Correlation-Id", existingId);
        assertNull(MDC.get("correlationId")); // Cleared after execution
    }
}

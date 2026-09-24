package com.nexus.supplychain.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitoringControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"https://metrics.example.test/d/nexus-overview", "http://localhost:3000", "https://metrics.example.test/grafana/?orgId=1"})
    void returnsConfiguredAbsoluteHttpLink(String url) throws Exception {
        MockMvcBuilders.standaloneSetup(new MonitoringController(url)).build()
                .perform(get("/api/v1/monitoring")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.grafanaUrl").value(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/grafana", "//metrics.example.test", "javascript:alert(1)",
            "https://user:secret@metrics.example.test", "https://metrics.example.test:99999", "http://", "not a URL",
            "https://metrics.example.test\\@evil.example.test", "https://metrics.example.test\n"})
    void disablesInvalidOrMissingConfiguration(String url) throws Exception {
        MockMvcBuilders.standaloneSetup(new MonitoringController(url)).build()
                .perform(get("/api/v1/monitoring")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.grafanaUrl").value(nullValue()));
    }
}

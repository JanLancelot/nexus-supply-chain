package com.nexus.supplychain.integration;

import com.nexus.supplychain.service.MonitoringMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@TestPropertySource(properties = {
        "spring.config.import=classpath:monitoring.properties",
        "spring.application.name=nexus-supply-chain",
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.metrics.tags.environment=test",
        "app.monitoring.metrics-enabled=false",
        "app.seed-demo-data=false"
})
class MonitoringIntegrationTest extends BaseIntegrationTest {
    @LocalServerPort
    private int applicationPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired
    private MonitoringMetrics metrics;

    @Test
    void privateManagementListenerExportsRealHttpHistogramAndBackgroundSnapshots() {
        assertNotEquals(applicationPort, managementPort);
        given().port(applicationPort).get("/actuator/prometheus").then().statusCode(401);
        given().port(applicationPort).get("/api/health").then().statusCode(200);
        metrics.refreshBusiness();
        metrics.refreshDependencies();

        String scrape = given().port(managementPort).get("/actuator/prometheus")
                .then().statusCode(200).extract().asString();
        assertTrue(scrape.contains("http_server_requests_seconds_bucket{"));
        assertTrue(scrape.contains("uri=\"/api/health\""));
        assertTrue(scrape.lines().anyMatch(line -> line.startsWith("http_server_requests_seconds_bucket{")
                && line.contains("le=\"0.5\"")), "The inventory latency objective needs an exact 500 ms bucket");
        assertTrue(scrape.contains("application=\"nexus-supply-chain\""));
        assertTrue(scrape.contains("environment=\"test\""));
        assertTrue(scrape.lines().anyMatch(line -> line.startsWith("nexus_inventory_products{") && !line.endsWith("NaN")));
        assertTrue(scrape.lines().anyMatch(line -> line.startsWith("nexus_observability_refresh_success{")
                && line.contains("component=\"business\"") && line.endsWith(" 1.0")));
        for (var status : com.nexus.supplychain.model.OrderStatus.values()) {
            assertTrue(scrape.lines().anyMatch(line -> line.startsWith("nexus_orders{")
                    && line.contains("status=\"" + status + "\"")));
        }
        assertFalse(scrape.contains("supplier="));
        assertFalse(scrape.contains("email="));
    }
}

package com.nexus.supplychain.service;

import com.nexus.supplychain.config.ResilientCacheManager;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.model.OrderStatus;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringMetricsTest {
    private MonitoringSnapshotReader reader;
    private KafkaLiteBroker broker;
    private ResilientCacheManager cache;
    private PrometheusMeterRegistry registry;
    private MonitoringMetrics metrics;

    @BeforeEach
    void setUp() {
        reader = mock(MonitoringSnapshotReader.class);
        broker = mock(KafkaLiteBroker.class);
        cache = mock(ResilientCacheManager.class);
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        when(broker.isRequired()).thenReturn(true);
        metrics = new MonitoringMetrics(reader, broker, cache, registry, 30000, false);
        clearInvocations(broker);
    }

    @Test
    void scrapesNeverReadTheDatabaseOrProbeDependenciesAndInitialValuesAreUnknown() {
        String scrape = registry.scrape();
        assertTrue(scrape.contains("nexus_inventory_products NaN"));
        assertTrue(scrape.contains("nexus_observability_last_success_timestamp_seconds{component=\"business\"} 0.0"));
        assertTrue(scrape.contains("nexus_events_required 1.0"));
        registry.scrape();
        verifyNoInteractions(reader, broker, cache);
    }

    @Test
    void publishesBoundedSeriesAndRetainsLastSnapshotOnFailure() {
        when(reader.read()).thenReturn(new MonitoringSnapshotReader.Snapshot(12, 2, 90, 1234.5,
                Map.of(OrderStatus.APPROVED, 3L)));
        metrics.refreshBusiness();
        double lastSuccess = registry.get("nexus.observability.last.success.timestamp.seconds")
                .tag("component", "business").gauge().value();
        assertTrue(lastSuccess > 0);
        String scrape = registry.scrape();
        assertTrue(scrape.contains("nexus_inventory_products 12.0"));
        assertTrue(scrape.contains("nexus_inventory_low_stock_products 2.0"));
        assertTrue(scrape.contains("nexus_inventory_units 90.0"));
        assertTrue(scrape.contains("nexus_inventory_value 1234.5"));
        assertTrue(scrape.contains("nexus_orders{status=\"APPROVED\"} 3.0"));
        assertTrue(scrape.contains("nexus_orders{status=\"DRAFT\"} 0.0"));
        assertEquals(OrderStatus.values().length, registry.find("nexus.orders").gauges().size());

        when(reader.read()).thenThrow(new IllegalStateException("Database offline"));
        assertDoesNotThrow(metrics::refreshBusiness);
        scrape = registry.scrape();
        assertTrue(scrape.contains("nexus_inventory_products 12.0"));
        assertTrue(scrape.contains("nexus_observability_refresh_success{component=\"business\"} 0.0"));
        assertTrue(scrape.contains("nexus_observability_refresh_failures_total{component=\"business\"} 1.0"));
        assertEquals(lastSuccess, registry.get("nexus.observability.last.success.timestamp.seconds")
                .tag("component", "business").gauge().value());
        assertEquals(2, registry.get("nexus.observability.refresh.duration").tag("component", "business").timer().count());
        verify(reader, times(2)).read();
        verifyNoInteractions(broker, cache);

        doReturn(new MonitoringSnapshotReader.Snapshot(13, 0, 100, 1500, Map.of())).when(reader).read();
        metrics.refreshBusiness();
        assertEquals(1, registry.get("nexus.observability.refresh.success").tag("component", "business").gauge().value());
        assertEquals(13, registry.get("nexus.inventory.products").gauge().value());
    }

    @Test
    void dependencyProbesDistinguishHealthyFallbackAndProbeFailureWithoutBlockingOtherProbe() {
        when(broker.healthStatus()).thenReturn("KAFKA_UP");
        when(cache.healthStatus()).thenReturn("UP");
        metrics.refreshDependencies();
        assertEquals(1, dependency("events"));
        assertEquals(1, dependency("cache"));

        when(broker.healthStatus()).thenReturn("MEMORY_FALLBACK");
        when(cache.healthStatus()).thenReturn("DEGRADED");
        metrics.refreshDependencies();
        assertEquals(0, dependency("events"));
        assertEquals(0, dependency("cache"));
        assertEquals(1, registry.get("nexus.observability.refresh.success").tag("component", "dependencies").gauge().value());

        when(broker.healthStatus()).thenThrow(new IllegalStateException("Probe failed"));
        when(cache.healthStatus()).thenReturn("UP");
        assertDoesNotThrow(metrics::refreshDependencies);
        assertEquals(0, dependency("events"));
        assertEquals(1, dependency("cache"));
        assertEquals(0, registry.get("nexus.observability.refresh.success").tag("component", "dependencies").gauge().value());
        assertEquals(1, registry.get("nexus.observability.refresh.failures").tag("component", "dependencies").counter().count());
        clearInvocations(reader, broker, cache);
        registry.scrape();
        verifyNoInteractions(reader, broker, cache);
    }

    private double dependency(String name) {
        return registry.get("nexus.dependency.up").tag("dependency", name).gauge().value();
    }
}

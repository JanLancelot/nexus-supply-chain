package com.nexus.supplychain.service;

import com.nexus.supplychain.config.ResilientCacheManager;
import com.nexus.supplychain.kafkalite.KafkaLiteBroker;
import com.nexus.supplychain.model.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/** Scrapes read only published in-memory values; dependency failures cannot fail a scrape. */
@Component
@Slf4j
public class MonitoringMetrics {
    private final MonitoringSnapshotReader reader;
    private final KafkaLiteBroker broker;
    private final CacheManager cacheManager;
    private final long intervalMs;
    private final boolean enabled;
    private final RefreshState business;
    private final RefreshState dependencies;
    private volatile MonitoringSnapshotReader.Snapshot snapshot;
    private volatile double eventsUp = Double.NaN;
    private volatile double cacheUp = Double.NaN;
    private ScheduledExecutorService executor;

    public MonitoringMetrics(MonitoringSnapshotReader reader, KafkaLiteBroker broker,
                             CacheManager cacheManager, MeterRegistry registry,
                             @Value("${app.monitoring.snapshot-interval-ms:30000}") long intervalMs,
                             @Value("${app.monitoring.metrics-enabled:true}") boolean enabled) {
        this.reader = reader;
        this.broker = broker;
        this.cacheManager = cacheManager;
        // Guard against accidental tight-loop DB polling. Larger intervals require a matching stale alert.
        this.intervalMs = Math.max(5000, intervalMs);
        this.enabled = enabled;
        business = new RefreshState(registry, "business");
        dependencies = new RefreshState(registry, "dependencies");
        inventoryGauge(registry, "nexus.inventory.products", "Catalog products, including inactive products",
                MonitoringSnapshotReader.Snapshot::products);
        inventoryGauge(registry, "nexus.inventory.low.stock.products", "Active products below their reorder level",
                MonitoringSnapshotReader.Snapshot::lowStockProducts);
        inventoryGauge(registry, "nexus.inventory.units", "Total stock units of active products",
                MonitoringSnapshotReader.Snapshot::units);
        inventoryGauge(registry, "nexus.inventory.value", "Active inventory value in the application's currency",
                MonitoringSnapshotReader.Snapshot::inventoryValue);
        for (OrderStatus status : OrderStatus.values()) {
            Gauge.builder("nexus.orders", this, metrics -> metrics.snapshot == null ? Double.NaN
                            : metrics.snapshot.orders().getOrDefault(status, 0L).doubleValue())
                    .tag("status", status.name()).description("Purchase orders in each lifecycle status").register(registry);
        }
        Gauge.builder("nexus.dependency.up", this, metrics -> metrics.eventsUp)
                .tag("dependency", "events").description("1 when Kafka is healthy; 0 for down or fallback").register(registry);
        Gauge.builder("nexus.dependency.up", this, metrics -> metrics.cacheUp)
                .tag("dependency", "cache").description("1 when the cache is healthy; 0 when degraded or unknown").register(registry);
        final double eventsRequired = broker.isRequired() ? 1 : 0;
        Gauge.builder("nexus.events.required", () -> eventsRequired)
                .description("1 when Kafka is required by deployment configuration").register(registry);
    }

    private void inventoryGauge(MeterRegistry registry, String name, String description,
                                ToDoubleFunction<MonitoringSnapshotReader.Snapshot> value) {
        Gauge.builder(name, this, metrics -> {
            MonitoringSnapshotReader.Snapshot current = metrics.snapshot;
            return current == null ? Double.NaN : value.applyAsDouble(current);
        }).description(description).register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || executor != null) {
            return;
        }
        // Dedicated threads keep slow probes isolated from API handlers and notification pruning.
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "monitoring-snapshot");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshBusiness, 0, intervalMs, TimeUnit.MILLISECONDS);
        executor.scheduleWithFixedDelay(this::refreshDependencies, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    public void refreshBusiness() {
        business.refresh(() -> snapshot = reader.read());
    }

    public void refreshDependencies() {
        dependencies.refresh(() -> {
            boolean failed = false;
            try {
                eventsUp = "KAFKA_UP".equals(broker.healthStatus()) ? 1 : 0;
            } catch (RuntimeException failure) {
                eventsUp = 0;
                failed = true;
            }
            try {
                cacheUp = cacheManager instanceof ResilientCacheManager resilient
                        && "UP".equals(resilient.healthStatus()) ? 1 : 0;
            } catch (RuntimeException failure) {
                cacheUp = 0;
                failed = true;
            }
            if (failed) {
                throw new IllegalStateException("Dependency probe failed");
            }
        });
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static final class RefreshState {
        private final String component;
        private final Counter failures;
        private final Timer duration;
        private volatile double success;
        private volatile double lastSuccess;

        private RefreshState(MeterRegistry registry, String component) {
            this.component = component;
            failures = Counter.builder("nexus.observability.refresh.failures").tag("component", component)
                    .description("Failed background metric refreshes").register(registry);
            duration = Timer.builder("nexus.observability.refresh.duration").tag("component", component)
                    .description("Background metric refresh duration").register(registry);
            Gauge.builder("nexus.observability.refresh.success", this, state -> state.success)
                    .tag("component", component).description("1 if the latest refresh succeeded, otherwise 0").register(registry);
            Gauge.builder("nexus.observability.last.success.timestamp.seconds", this, state -> state.lastSuccess)
                    .tag("component", component).description("Unix time of latest successful refresh; 0 before first success")
                    .register(registry);
        }

        private void refresh(Runnable refresh) {
            duration.record(() -> {
                try {
                    refresh.run();
                    lastSuccess = System.currentTimeMillis() / 1000.0;
                    success = 1;
                } catch (RuntimeException failure) {
                    failures.increment();
                    if (success == 1 || failures.count() == 1) {
                        log.warn("Monitoring {} snapshot failed ({}); retaining last successful snapshot",
                                component, failure.getClass().getSimpleName());
                    }
                    success = 0;
                }
            });
        }
    }
}

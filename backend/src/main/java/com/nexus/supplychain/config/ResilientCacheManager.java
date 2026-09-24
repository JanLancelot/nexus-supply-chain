package com.nexus.supplychain.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/** A failed cache is bypassed until its namespace can be cleared, then reads can repopulate it. */
public class ResilientCacheManager implements CacheManager {
    private final CacheManager delegate;
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();

    public ResilientCacheManager(CacheManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, key -> {
            Cache cache = delegate.getCache(key);
            return cache == null ? null : new ResilientCache(cache);
        });
    }

    public String healthStatus() {
        Cache probe = getCache("health");
        if (probe != null) {
            probe.get("dependency-probe");
        }
        return caches.values().stream().map(ResilientCache.class::cast).allMatch(ResilientCache::isAvailable)
                ? "UP" : "DEGRADED";
    }

    @Override
    public Collection<String> getCacheNames() {
        return delegate.getCacheNames();
    }

    @Slf4j
    public static class ResilientCache implements Cache {
        private final Cache delegate;
        private volatile boolean available = true;
        private volatile long nextRecoveryAttempt;
        private final long recoveryDelayMs;
        private volatile long generation;
        private final ThreadLocal<ReadVersion> lastMiss = new ThreadLocal<>();
        private record ReadVersion(Object key, long generation) {}

        public ResilientCache(Cache delegate) {
            this(delegate, 1000);
        }

        ResilientCache(Cache delegate, long recoveryDelayMs) {
            this.delegate = delegate;
            this.recoveryDelayMs = recoveryDelayMs;
        }

        public boolean isAvailable() {
            return available;
        }

        @Override public String getName() { return delegate.getName(); }
        @Override public Object getNativeCache() { return delegate.getNativeCache(); }

        @Override
        public synchronized ValueWrapper get(Object key) {
            lastMiss.remove();
            ValueWrapper result = null;
            if (readyForRead()) {
                try {
                    result = delegate.get(key);
                } catch (RuntimeException failure) {
                    failed(failure);
                }
            }
            if (result == null) {
                lastMiss.set(new ReadVersion(key, generation));
            }
            return result;
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            ValueWrapper value = get(key);
            if (value == null) {
                return null;
            }
            Object result = value.get();
            if (result != null && type != null && !type.isInstance(result)) {
                throw new IllegalStateException("Cached value does not match requested type " + type.getName());
            }
            @SuppressWarnings("unchecked") T cast = (T) result;
            return cast;
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            ValueWrapper cached = get(key);
            if (cached != null) {
                @SuppressWarnings("unchecked") T value = (T) cached.get();
                return value;
            }
            try {
                T value = valueLoader.call();
                put(key, value);
                return value;
            } catch (Exception failure) {
                throw new ValueRetrievalException(key, valueLoader, failure);
            }
        }

        @Override
        public synchronized void put(Object key, Object value) {
            ReadVersion miss = lastMiss.get();
            lastMiss.remove();
            boolean currentRead = miss == null || !java.util.Objects.equals(miss.key(), key)
                    || miss.generation() == generation;
            // Only a read may recover; reject a loader result that began before a failed eviction.
            if (available && currentRead) {
                try {
                    delegate.put(key, value);
                } catch (RuntimeException failure) {
                    failed(failure);
                }
            }
        }

        @Override
        public synchronized void evict(Object key) {
            generation++;
            if (available) {
                try {
                    delegate.evict(key);
                } catch (RuntimeException failure) {
                    failed(failure);
                }
            }
        }

        @Override
        public synchronized void clear() {
            generation++;
            if (available) {
                try {
                    delegate.clear();
                } catch (RuntimeException failure) {
                    failed(failure);
                }
            }
        }

        private synchronized boolean readyForRead() {
            if (available) {
                return true;
            }
            if (System.currentTimeMillis() < nextRecoveryAttempt) {
                return false;
            }
            try {
                delegate.clear();
                generation++;
                available = true;
                log.info("Cache {} recovered after clearing potentially stale entries", getName());
            } catch (RuntimeException failure) {
                failed(failure);
            }
            return available;
        }

        private synchronized void failed(RuntimeException failure) {
            if (available) {
                log.warn("Cache {} unavailable; reads will use the database: {}", getName(), failure.getMessage());
            }
            generation++;
            available = false;
            nextRecoveryAttempt = System.currentTimeMillis() + recoveryDelayMs;
        }
    }
}

package com.pg.supplychain.kafkalite;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
@Slf4j
public class RedisKafkaLiteBroker implements KafkaLiteBroker {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    // In-memory queue fallback when Redis is offline
    private final Map<String, LinkedBlockingQueue<String>> inMemoryQueues = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
    private volatile boolean useFallback = false;
    private volatile boolean running = true;

    public RedisKafkaLiteBroker(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // Bounded thread pool: 4 core threads, 16 max, 60s idle keepalive, 200-item queue.
        // Prevents unbounded thread proliferation under high concurrency across multiple test runs.
        this.executorService = new ThreadPoolExecutor(
                4, 16, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Test connection on start asynchronously to avoid blocking startup
        executorService.submit(() -> {
            try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
                connection.ping();
                log.info("KafkaLite Broker: Successfully connected to Redis event queue broker.");
            } catch (Exception e) {
                log.warn("KafkaLite Broker: Redis is not available. Falling back to in-memory event queues. Error: {}", e.getMessage());
                this.useFallback = true;
            }
        });
    }

    @Override
    public void send(String topic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (!useFallback) {
                try {
                    redisTemplate.opsForList().rightPush("kafka_topic_" + topic, json);
                    log.debug("KafkaLite: Sent message to topic {}: {}", topic, json);
                    return;
                } catch (Exception e) {
                    log.warn("KafkaLite: Failed to send message to Redis. Switching to in-memory fallback. Error: {}", e.getMessage());
                    useFallback = true;
                }
            }
            
            // Fallback to in-memory queue
            inMemoryQueues.computeIfAbsent(topic, k -> new LinkedBlockingQueue<>()).offer(json);
            log.debug("KafkaLite (InMemory): Sent message to topic {}: {}", topic, json);
        } catch (Exception e) {
            log.error("KafkaLite: Failed to serialize and send event to topic: " + topic, e);
        }
    }

    @Override
    public void subscribe(String topic, Consumer<String> handler) {
        boolean startWorker = false;
        synchronized (subscribers) {
            List<Consumer<String>> list = subscribers.get(topic);
            if (list == null) {
                list = new CopyOnWriteArrayList<>();
                subscribers.put(topic, list);
                startWorker = true;
            }
            list.add(handler);
        }
        
        if (startWorker) {
            // Start background worker for this topic only once
            executorService.submit(() -> pollTopic(topic));
            log.info("KafkaLite: Subscribed and started polling thread for topic {}", topic);
        } else {
            log.info("KafkaLite: Added subscriber to existing topic {}", topic);
        }
    }

    private void pollTopic(String topic) {
        String redisKey = "kafka_topic_" + topic;
        while (running) {
            try {
                String message = null;
                if (!useFallback) {
                    try {
                        // Perform a non-blocking pop
                        message = redisTemplate.opsForList().leftPop(redisKey);
                        if (message == null) {
                            TimeUnit.MILLISECONDS.sleep(100);
                        }
                    } catch (Exception e) {
                        log.warn("KafkaLite: Connection issue during polling for {}. Switching to in-memory fallback. Error: {}", topic, e.getMessage());
                        useFallback = true;
                    }
                }

                if (useFallback) {
                    // Poll from local in-memory queue
                    LinkedBlockingQueue<String> queue = inMemoryQueues.computeIfAbsent(topic, k -> new LinkedBlockingQueue<>());
                    message = queue.poll(100, TimeUnit.MILLISECONDS);
                }

                if (message != null) {
                    List<Consumer<String>> handlers = subscribers.get(topic);
                    if (handlers != null) {
                        for (Consumer<String> handler : handlers) {
                            try {
                                handler.accept(message);
                            } catch (Exception e) {
                                log.error("KafkaLite: Error in handler for topic " + topic, e);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("KafkaLite: Unexpected error polling topic " + topic, e);
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        log.info("KafkaLite Broker: Shutdown completed.");
    }
}

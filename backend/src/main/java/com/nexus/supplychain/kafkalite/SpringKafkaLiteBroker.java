package com.nexus.supplychain.kafkalite;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
@Primary
@Slf4j
public class SpringKafkaLiteBroker implements KafkaLiteBroker, SmartInitializingSingleton {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisKafkaLiteBroker redisFallbackBroker;
    private final ObjectMapper objectMapper;
    private final String bootstrapServers;
    private final String consumerGroup;
    private final Map<String, KafkaMessageListenerContainer<String, String>> containers = new ConcurrentHashMap<>();
    private final boolean kafkaActive;
    private final boolean kafkaRequired;
    private volatile boolean started = false;

    public SpringKafkaLiteBroker(
            KafkaTemplate<String, String> kafkaTemplate,
            RedisKafkaLiteBroker redisFallbackBroker,
            ObjectMapper objectMapper,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:nexus-supply-chain}") String consumerGroup,
            @Value("${app.events.kafka-required:false}") boolean kafkaRequired,
            @Value("${app.events.kafka-startup-timeout-ms:10000}") int startupTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisFallbackBroker = redisFallbackBroker;
        this.objectMapper = objectMapper;
        this.bootstrapServers = bootstrapServers;
        this.consumerGroup = consumerGroup;
        this.kafkaRequired = kafkaRequired;

        int probeTimeoutMs = kafkaRequired ? Math.max(1500, startupTimeoutMs) : 1500;
        boolean available = false;
        try {
            Map<String, Object> config = new HashMap<>();
            config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, probeTimeoutMs);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, probeTimeoutMs);
            try (AdminClient adminClient = AdminClient.create(config)) {
                adminClient.listTopics().names().get(probeTimeoutMs, TimeUnit.MILLISECONDS);
                available = true;
                log.info("Kafka broker is available at {}", bootstrapServers);
            }
        } catch (Exception e) {
            log.warn("SpringKafkaLiteBroker: Apache Kafka is OFFLINE at {}. Falling back to Redis/In-Memory broker. Details: {}", bootstrapServers, e.getMessage());
            if (kafkaRequired) {
                throw new IllegalStateException("Kafka is required but unavailable at " + bootstrapServers, e);
            }
        }
        this.kafkaActive = available;
    }

    @Override
    public boolean isRequired() {
        return kafkaRequired;
    }

    @Override
    public String healthStatus() {
        if (!kafkaActive) {
            return redisFallbackBroker.healthStatus();
        }
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1000);
        AdminClient client = AdminClient.create(config);
        try {
            client.describeCluster().clusterId().get(1000, TimeUnit.MILLISECONDS);
            return "KAFKA_UP";
        } catch (Exception failure) {
            return "KAFKA_DOWN";
        } finally {
            client.close(java.time.Duration.ZERO);
        }
    }

    @Override
    public void send(String topic, Object payload) {
        TransactionEventPublication.afterCommit(() -> sendImmediately(topic, payload));
    }

    private void sendImmediately(String topic, Object payload) {
        if (!kafkaActive) {
            redisFallbackBroker.sendImmediately(topic, payload);
            return;
        }

        try {
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, json).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("SpringKafkaLiteBroker: Failed to send event to topic " + topic, ex);
                } else {
                    log.debug("SpringKafkaLiteBroker: Sent message to topic {}: {}", topic, json);
                }
            });
        } catch (Exception e) {
            log.error("SpringKafkaLiteBroker: Failed to serialize event for topic " + topic, e);
        }
    }

    @Override
    public void subscribe(String topic, Consumer<String> handler) {
        subscribe(topic, "default", handler);
    }

    @Override
    public synchronized void subscribe(String topic, String subscriberId, Consumer<String> handler) {
        if (!kafkaActive) {
            redisFallbackBroker.subscribe(topic, subscriberId, handler);
            return;
        }
        String subscription = topic + ":" + subscriberId;
        if (containers.containsKey(subscription)) {
            throw new IllegalArgumentException("Duplicate Kafka subscription " + subscription);
        }
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Keep the old offsets for notifications and queued legacy audits during upgrades.
        String suffix = ("notifications".equals(subscriberId) || "legacy-audit".equals(subscriberId))
                ? "" : "-" + subscriberId;
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup + "-" + topic + suffix);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ContainerProperties properties = new ContainerProperties(topic);
        properties.setAckMode(ContainerProperties.AckMode.RECORD);
        properties.setMessageListener((MessageListener<String, String>) record -> handler.accept(record.value()));
        KafkaMessageListenerContainer<String, String> container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<>(props), properties);
        container.setCommonErrorHandler(errorHandler(kafkaTemplate, subscriberId));
        containers.put(subscription, container);
        if (started) {
            container.start();
        }
    }

    static DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate, String subscriberId) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + "." + subscriberId + ".DLT", -1));
        // A failed DLT publication must keep the source offset eligible for redelivery.
        recoverer.setFailIfSendResultIsError(true);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        handler.setResetStateOnRecoveryFailure(true);
        return handler;
    }

    @Override
    public synchronized void afterSingletonsInstantiated() {
        started = true;
        containers.values().forEach(KafkaMessageListenerContainer::start);
    }

    @PreDestroy
    public void shutdown() {
        containers.values().forEach(KafkaMessageListenerContainer::stop);
    }
}

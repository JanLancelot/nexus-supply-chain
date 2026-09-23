package com.pg.supplychain.kafkalite;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, KafkaMessageListenerContainer<String, String>> containers = new ConcurrentHashMap<>();
    private boolean kafkaActive = false;
    private volatile boolean started = false;

    public SpringKafkaLiteBroker(
            KafkaTemplate<String, String> kafkaTemplate,
            RedisKafkaLiteBroker redisFallbackBroker,
            ObjectMapper objectMapper,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id:nexus-supply-chain}") String consumerGroup) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisFallbackBroker = redisFallbackBroker;
        this.objectMapper = objectMapper;
        this.bootstrapServers = bootstrapServers;
        this.consumerGroup = consumerGroup;

        // Check if Kafka is reachable on start
        try {
            Map<String, Object> config = new HashMap<>();
            config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500);
            config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1500);
            try (AdminClient adminClient = AdminClient.create(config)) {
                adminClient.listTopics().names().get(1500, TimeUnit.MILLISECONDS);
                this.kafkaActive = true;
                log.info("Kafka broker is available at {}", bootstrapServers);
            }
        } catch (Exception e) {
            log.warn("SpringKafkaLiteBroker: Apache Kafka is OFFLINE at {}. Falling back to Redis/In-Memory broker. Details: {}", bootstrapServers, e.getMessage());
            this.kafkaActive = false;
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
    public synchronized void subscribe(String topic, Consumer<String> handler) {
        if (!kafkaActive) {
            redisFallbackBroker.subscribe(topic, handler);
            return;
        }

        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(handler);

        containers.computeIfAbsent(topic, t -> {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup + "-" + t);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

            DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(props);
            ContainerProperties containerProperties = new ContainerProperties(t);
            containerProperties.setMessageListener((MessageListener<String, String>) record -> {
                List<Consumer<String>> handlers = subscribers.get(t);
                if (handlers != null) {
                    for (Consumer<String> h : handlers) {
                        try {
                            h.accept(record.value());
                        } catch (Exception e) {
                            log.error("SpringKafkaLiteBroker: Error in handler for topic " + t, e);
                        }
                    }
                }
            });

            KafkaMessageListenerContainer<String, String> container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
            if (started) {
                container.start();
            }
            return container;
        });
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

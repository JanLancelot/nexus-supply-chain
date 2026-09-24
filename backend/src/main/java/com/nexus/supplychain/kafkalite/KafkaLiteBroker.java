package com.nexus.supplychain.kafkalite;

import java.util.function.Consumer;

public interface KafkaLiteBroker {
    void send(String topic, Object payload);
    void subscribe(String topic, Consumer<String> handler);

    default String healthStatus() { return "UNKNOWN"; }
    default boolean isRequired() { return false; }

    /** Stable subscriber identity allows durable brokers to retry each consumer independently. */
    default void subscribe(String topic, String subscriberId, Consumer<String> handler) {
        subscribe(topic, handler);
    }
}

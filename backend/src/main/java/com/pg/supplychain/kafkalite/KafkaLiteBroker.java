package com.pg.supplychain.kafkalite;

import java.util.function.Consumer;

public interface KafkaLiteBroker {
    void send(String topic, Object payload);
    void subscribe(String topic, Consumer<String> handler);
}

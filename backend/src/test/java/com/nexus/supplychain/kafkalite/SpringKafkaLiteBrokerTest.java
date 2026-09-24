package com.nexus.supplychain.kafkalite;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringKafkaLiteBrokerTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void failedDeadLetterWriteLeavesTheSourceRecordEligibleForRetry() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ProducerFactory<String, String> factory = mock(ProducerFactory.class);
        when(template.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(Map.of(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 100));
        when(template.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        var handler = SpringKafkaLiteBroker.errorHandler(template, "notifications");
        handler.setBackOffFunction((record, failure) -> new FixedBackOff(0, 1));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order-events", 0, 15, "key", "payload");
        Consumer consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        RuntimeException failure = new IllegalStateException("database unavailable");
        assertFalse(handler.handleOne(failure, record, consumer, container));
        assertFalse(handler.handleOne(failure, record, consumer, container), "Failed DLT write must not recover the offset");
        assertFalse(handler.handleOne(failure, record, consumer, container));
        assertTrue(handler.handleOne(failure, record, consumer, container), "Only a confirmed DLT write recovers the record");
        ArgumentCaptor<ProducerRecord<String, String>> publication = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template, times(2)).send(publication.capture());
        assertTrue(publication.getAllValues().stream().allMatch(sent ->
                "order-events.notifications.DLT".equals(sent.topic()) && "payload".equals(sent.value())));
    }
}

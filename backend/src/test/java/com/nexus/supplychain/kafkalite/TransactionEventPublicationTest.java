package com.nexus.supplychain.kafkalite;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionEventPublicationTest {
    private final List<String> sent = new ArrayList<>();
    private final TransactionTemplate transactions = new TransactionTemplate(new AbstractPlatformTransactionManager() {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    });

    @Test
    void publishesOnlyAfterSuccessfulCommit() {
        transactions.executeWithoutResult(status -> {
            TransactionEventPublication.afterCommit(() -> sent.add("committed"));
            assertTrue(sent.isEmpty(), "Listeners must not observe uncommitted changes");
        });
        assertEquals(List.of("committed"), sent);
    }

    @Test
    void discardsPublicationWhenTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            TransactionEventPublication.afterCommit(() -> sent.add("rolled back"));
            status.setRollbackOnly();
        });
        assertTrue(sent.isEmpty());
    }

    @Test
    void publishesImmediatelyWithoutTransaction() {
        TransactionEventPublication.afterCommit(() -> sent.add("immediate"));
        assertEquals(List.of("immediate"), sent);
    }
}

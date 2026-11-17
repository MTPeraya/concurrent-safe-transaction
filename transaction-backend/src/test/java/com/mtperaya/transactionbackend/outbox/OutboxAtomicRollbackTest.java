package com.mtperaya.transactionbackend.outbox;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.OutboxEvent;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import com.mtperaya.transactionbackend.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class OutboxAtomicRollbackTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Account sender;
    private Account receiver;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();

        sender = new Account();
        sender.setOwnerName("Alice");
        sender.setBalance(new BigDecimal("500.00"));
        sender = accountRepository.save(sender);

        receiver = new Account();
        receiver.setOwnerName("Bob");
        receiver.setBalance(new BigDecimal("100.00"));
        receiver = accountRepository.save(receiver);
    }

    @Test
    void shouldCreateOutboxEventOnSuccessfulTransfer() {
        transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("100.00")
        );

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(1, events.size(), "Exactly 1 outbox event should be recorded");

        OutboxEvent event = events.get(0);
        assertEquals(sender.getId().toString(), event.getAggregateId());
        assertEquals("TransferCompleted", event.getEventType());
        assertNull(event.getPublishedAt());
        assertNotNull(event.getPayload());
        assertTrue(event.getPayload().contains("\"eventType\":\"TransferCompleted\""));
        assertTrue(event.getPayload().contains("\"amount\":\"100.00\""));
    }

    @Test
    void shouldNotCreateOutboxEventWhenTransferFailsDueToInsufficientFunds() {
        assertThrows(Exception.class, () -> transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("1000.00") // Exceeds balance of 500.00
        ));

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertEquals(0, events.size(), "No outbox event should exist on failed transfer");
    }
}

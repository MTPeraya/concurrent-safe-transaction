package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.exception.IdempotencyConflictException;
import com.mtperaya.transactionbackend.exception.InsufficientFundsException;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class TransactionIdempotencyTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Account sender;
    private Account receiver;

    @BeforeEach
    void setUp() {

        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        sender = new Account();
        sender.setOwnerName("Alice");
        sender.setBalance(new BigDecimal("500.00"));
        sender = accountRepository.saveAndFlush(sender);

        receiver = new Account();
        receiver.setOwnerName("Bob");
        receiver.setBalance(new BigDecimal("100.00"));
        receiver = accountRepository.saveAndFlush(receiver);
    }

    @Test
    void shouldNotExecuteSameRequestTwice() {

        String key = "payment-001";

        Transaction first = transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("100.00"),
                key
        );

        Transaction second = transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("100.00"),
                key
        );

        assertEquals(first.getId(), second.getId());

        assertEquals(1, transactionRepository.count());

        assertEquals(
                0,
                new BigDecimal("400.00").compareTo(
                        accountRepository.findById(sender.getId())
                                .orElseThrow().getBalance()
                )
        );

        assertEquals(
                0,
                new BigDecimal("200.00").compareTo(
                        accountRepository.findById(receiver.getId())
                                .orElseThrow().getBalance()
                )
        );
    }

    @Test
    void shouldRejectSameKeyWithDifferentRequest() {

        String key = "payment-002";

        transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("100.00"),
                key
        );

        assertThrows(
                IdempotencyConflictException.class,
                () -> transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("200.00"),
                        key
                )
        );

        assertEquals(1, transactionRepository.count());

        assertEquals(
                0,
                new BigDecimal("400.00").compareTo(
                        accountRepository.findById(sender.getId())
                                .orElseThrow().getBalance()
                )
        );
    }

    @Test
    void shouldProcessConcurrentRequestsWithSameKeyOnlyOnce()
            throws Exception {

        int numberOfRequests = 12;

        String key = "payment-concurrent-001";

        ExecutorService executor =
                Executors.newFixedThreadPool(numberOfRequests);

        CountDownLatch readyLatch =
                new CountDownLatch(numberOfRequests);

        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Transaction>> futures = new ArrayList<>();

        try {

            for (int i = 0; i < numberOfRequests; i++) {

                Future<Transaction> future = executor.submit(() -> {

                    readyLatch.countDown();

                    startLatch.await();

                    return transactionService.transfer(
                            sender.getId(),
                            receiver.getId(),
                            new BigDecimal("100.00"),
                            key
                    );
                });

                futures.add(future);
            }

            assertTrue(
                    readyLatch.await(30, TimeUnit.SECONDS),
                    "Not all workers became ready"
            );

            startLatch.countDown();

            Set<UUID> transactionIds = new HashSet<>();

            for (Future<Transaction> future : futures) {
                Transaction result = future.get(60, TimeUnit.SECONDS);
                transactionIds.add(result.getId());
            }

            assertEquals(
                    1,
                    transactionIds.size(),
                    "All requests should receive the same transaction"
            );

            assertEquals(1, transactionRepository.count());

            assertEquals(
                    0,
                    new BigDecimal("400.00").compareTo(
                            accountRepository.findById(sender.getId())
                                    .orElseThrow().getBalance()
                    )
            );

            assertEquals(
                    0,
                    new BigDecimal("200.00").compareTo(
                            accountRepository.findById(receiver.getId())
                                    .orElseThrow().getBalance()
                    )
            );

        } finally {

            startLatch.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void shouldAllowRetryAfterFailedTransfer() {

        String key = "payment-retry-001";

        assertThrows(
                InsufficientFundsException.class,
                () -> transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("600.00"),
                        key
                )
        );

        // Add funds after the failed attempt.
        sender.setBalance(new BigDecimal("700.00"));
        accountRepository.saveAndFlush(sender);

        // Reuse the same key. It should now succeed.
        Transaction transaction = transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("600.00"),
                key
        );

        assertNotNull(transaction.getId());
        assertEquals(1, transactionRepository.count());

        assertEquals(
                0,
                new BigDecimal("100.00").compareTo(
                        accountRepository.findById(sender.getId())
                                .orElseThrow().getBalance()
                )
        );
    }
}
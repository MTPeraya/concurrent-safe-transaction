package com.mtperaya.transactionbackend.service;
import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class TransactionConcurrencyTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {

        // Clean previous test data
        outboxEventRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // Alice starts with $1,000
        alice = new Account();
        alice.setOwnerName("Alice");
        alice.setBalance(new BigDecimal("1000.00"));
        alice = accountRepository.saveAndFlush(alice);

        // Bob starts with $0
        bob = new Account();
        bob.setOwnerName("Bob");
        bob.setBalance(new BigDecimal("0.00"));
        bob = accountRepository.saveAndFlush(bob);
    }

    @Test
    void shouldHandle100ConcurrentTransfersSafely() throws Exception {

        AtomicInteger completedTransfers = new AtomicInteger(0);
        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);

        // 1. Configure concurrency
        int numberOfTransfers = 100;

        BigDecimal transferAmount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(numberOfTransfers);

        CountDownLatch readyLatch = new CountDownLatch(numberOfTransfers);

        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();

        try {

            // 2. Submit 100 independent transfer tasks
            for (int i = 0; i < numberOfTransfers; i++) {

                Future<?> future = executor.submit(() -> {

                    // Signal that this worker is ready
                    readyLatch.countDown();

                    try {
                        // Wait until all workers are ready
                        startLatch.await();

                        // Perform transfer
                        transactionService.transfer(
                                alice.getId(),
                                bob.getId(),
                                transferAmount
                        );

                        successfulTransfers.incrementAndGet();

                        int completed = completedTransfers.incrementAndGet();

                        System.out.println(
                                "Transfer " + completed +
                                "/100 completed | Thread: " +
                                Thread.currentThread().getName()
                        );

                    } catch (InterruptedException e) {

                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);

                    } catch (ObjectOptimisticLockingFailureException e) {

                        failedTransfers.incrementAndGet();

                        System.out.println(
                                "Optimistic locking conflict | Thread: " +
                                Thread.currentThread().getName()
                        );
                    }
                });

                futures.add(future);
            }

            // 3. Wait until all workers are ready
            assertTrue(
                    readyLatch.await(30, TimeUnit.SECONDS),
                    "Not all workers became ready"
            );

            // 4. Release all workers simultaneously
            startLatch.countDown();

            // 5. Wait for every transfer to finish
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }

        } finally {

            // Always release waiting workers
            startLatch.countDown();

            // Always shut down the executor
            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();

                assertTrue(
                        executor.awaitTermination(30, TimeUnit.SECONDS),
                        "Executor did not terminate"
                );
            }
        }

        // 6. Retrieve final account balances
        Account updatedAlice = accountRepository
                .findById(alice.getId())
                .orElseThrow();

        Account updatedBob = accountRepository
                .findById(bob.getId())
                .orElseThrow();

        BigDecimal aliceBalance = updatedAlice.getBalance();
        BigDecimal bobBalance = updatedBob.getBalance();

        // 7. Verify total money is preserved
        BigDecimal totalBalance = aliceBalance.add(bobBalance);

        int successful = successfulTransfers.get();
        int failed = failedTransfers.get();

        System.out.println("\n========== CONCURRENCY RESULTS ==========");

        System.out.println("Transfers requested: 100");
        System.out.println("Successful transfers: " + successful);
        System.out.println("Failed transfers: " + failed);

        System.out.println("Alice balance: $" + aliceBalance);
        System.out.println("Bob balance: $" + bobBalance);

        System.out.println("Total balance: $" + totalBalance);

        System.out.println("Database transactions: "
                + transactionRepository.count());

        System.out.println("=========================================\n");

        // All requests must have finished
        assertEquals(100, successful + failed);

        // Money must be preserved
        assertEquals(
                0,
                new BigDecimal("1000.00").compareTo(totalBalance)
        );

        // Verify balances correspond to successful transfers
        // Verify Alice's balance
        assertEquals(
                0,
                new BigDecimal("1000.00")
                        .subtract(transferAmount.multiply(
                                BigDecimal.valueOf(successful)))
                        .compareTo(aliceBalance)
        );
        // Verify Bob's balance
        assertEquals(
                0,
                transferAmount.multiply(
                        BigDecimal.valueOf(successful))
                        .compareTo(bobBalance)
        );

        // Verify database transaction count
        assertEquals(
                successful,
                transactionRepository.count(),
                "Database transaction count must match successful transfers"
        );

        assertEquals(
            100,
            successful,
            "All 100 transfers must succeed"
        );

        assertEquals(
            0,
            failed,
            "No transfers should fail"
        );

        // AT-1: Assert that exactly 100 outbox events exist
        assertEquals(
            100,
            outboxEventRepository.count(),
            "Exactly 100 outbox events must be created"
        );

        assertEquals(
            100,
            outboxEventRepository.countByPublishedAtIsNull(),
            "All 100 outbox events must be unpublished initially"
        );
    }

    @Test
    void shouldPreventDeadlocksUnderOpposingTransfers() throws Exception {
        // Reset balances: Alice = $500, Bob = $500
        alice.setBalance(new BigDecimal("500.00"));
        accountRepository.saveAndFlush(alice);
        bob.setBalance(new BigDecimal("500.00"));
        accountRepository.saveAndFlush(bob);
        outboxEventRepository.deleteAll();
        transactionRepository.deleteAll();

        int totalTransfers = 100;
        BigDecimal amount = new BigDecimal("5.00");
        ExecutorService executor = Executors.newFixedThreadPool(totalTransfers);
        CountDownLatch readyLatch = new CountDownLatch(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successful = new AtomicInteger(0);
        AtomicInteger deadlocks = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < totalTransfers; i++) {
            final boolean aliceToBob = (i % 2 == 0); // 50 Alice->Bob, 50 Bob->Alice
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (aliceToBob) {
                        transactionService.transfer(alice.getId(), bob.getId(), amount);
                    } else {
                        transactionService.transfer(bob.getId(), alice.getId(), amount);
                    }
                    successful.incrementAndGet();
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("deadlock")) {
                        deadlocks.incrementAndGet();
                    }
                    System.err.println("Transfer failed: " + e.getMessage());
                }
            }));
        }

        assertTrue(readyLatch.await(30, TimeUnit.SECONDS));
        startLatch.countDown(); // Fire opposing transfers simultaneously

        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();

        Account finalAlice = accountRepository.findById(alice.getId()).orElseThrow();
        Account finalBob = accountRepository.findById(bob.getId()).orElseThrow();
        BigDecimal finalTotal = finalAlice.getBalance().add(finalBob.getBalance());

        // Assert 0 deadlocks (SQLSTATE 40P01 prevented by deterministic UUID total order)
        assertEquals(0, deadlocks.get(), "Zero deadlocks should occur under opposing transfers");
        assertEquals(100, successful.get(), "All 100 opposing transfers must succeed");
        assertEquals(0, new BigDecimal("1000.00").compareTo(finalTotal), "Total balance must remain strictly $1,000.00");
        assertEquals(100, outboxEventRepository.count(), "Exactly 100 outbox events recorded");
    }
}

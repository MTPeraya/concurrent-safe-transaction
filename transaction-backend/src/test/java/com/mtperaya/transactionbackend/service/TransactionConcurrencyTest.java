package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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
class TransactionConcurrencyTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {

        // Clean previous test data
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
    }
}

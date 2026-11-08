package com.mtperaya.transactionbackend.benchmark;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;
import com.mtperaya.transactionbackend.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LockingBenchmarkTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Test
    @DisplayName("Compare Pessimistic Locking vs Optimistic Locking under 100 concurrent transfers")
    void benchmarkPessimisticVsOptimistic() throws Exception {
        int concurrency = 100;
        BigDecimal transferAmount = new BigDecimal("5.00");
        BigDecimal initialBalance = new BigDecimal("1000.00");

        // ── 1. Benchmark Pessimistic Locking ──────────────────────────────
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account pFrom = createAccount("Pessimistic Sender", initialBalance);
        Account pTo = createAccount("Pessimistic Receiver", BigDecimal.ZERO);

        long startPessimistic = System.nanoTime();
        BenchmarkResult pResult = runConcurrentTransfers(
                pFrom.getId(), pTo.getId(), transferAmount, concurrency, false
        );
        long durationPessimisticMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startPessimistic);

        Account pFromUpdated = accountRepository.findById(pFrom.getId()).orElseThrow();
        Account pToUpdated = accountRepository.findById(pTo.getId()).orElseThrow();
        BigDecimal pTotal = pFromUpdated.getBalance().add(pToUpdated.getBalance());

        // ── 2. Benchmark Optimistic Locking ───────────────────────────────
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account oFrom = createAccount("Optimistic Sender", initialBalance);
        Account oTo = createAccount("Optimistic Receiver", BigDecimal.ZERO);

        long startOptimistic = System.nanoTime();
        BenchmarkResult oResult = runConcurrentTransfers(
                oFrom.getId(), oTo.getId(), transferAmount, concurrency, true
        );
        long durationOptimisticMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startOptimistic);

        Account oFromUpdated = accountRepository.findById(oFrom.getId()).orElseThrow();
        Account oToUpdated = accountRepository.findById(oTo.getId()).orElseThrow();
        BigDecimal oTotal = oFromUpdated.getBalance().add(oToUpdated.getBalance());

        // ── 3. Report Results ─────────────────────────────────────────────
        System.out.println("\n=======================================================");
        System.out.println("   CONCURRENCY LOCKING STRATEGY BENCHMARK REPORT       ");
        System.out.println("=======================================================");
        System.out.printf("Total Concurrent Transfers: %d%n", concurrency);
        System.out.println("-------------------------------------------------------");
        System.out.println("[PESSIMISTIC ROW-LEVEL LOCKING]");
        System.out.printf("  Execution Time:    %d ms%n", durationPessimisticMs);
        System.out.printf("  Successful:        %d / %d (%.1f%%)%n", pResult.success(), concurrency, (pResult.success() * 100.0 / concurrency));
        System.out.printf("  Conflicts/Failed:  %d%n", pResult.failed());
        System.out.printf("  Sender Final:      $%s%n", pFromUpdated.getBalance());
        System.out.printf("  Receiver Final:    $%s%n", pToUpdated.getBalance());
        System.out.printf("  Total Conserved:   $%s (Expected: $%s)%n", pTotal, initialBalance);
        System.out.println("-------------------------------------------------------");
        System.out.println("[OPTIMISTIC VERSION LOCKING]");
        System.out.printf("  Execution Time:    %d ms%n", durationOptimisticMs);
        System.out.printf("  Successful:        %d / %d (%.1f%%)%n", oResult.success(), concurrency, (oResult.success() * 100.0 / concurrency));
        System.out.printf("  Conflicts/Failed:  %d (ObjectOptimisticLockingFailureException)%n", oResult.failed());
        System.out.printf("  Sender Final:      $%s%n", oFromUpdated.getBalance());
        System.out.printf("  Receiver Final:    $%s%n", oToUpdated.getBalance());
        System.out.printf("  Total Conserved:   $%s (Expected: $%s)%n", oTotal, initialBalance);
        System.out.println("=======================================================\n");

        // Assertions: Money conservation must hold 100% in both models!
        assertThat(pTotal).isEqualByComparingTo(initialBalance);
        assertThat(oTotal).isEqualByComparingTo(initialBalance);

        // Pessimistic locking must have 100% success rate without conflicts
        assertThat(pResult.success()).isEqualTo(concurrency);
        assertThat(pResult.failed()).isEqualTo(0);

        // Optimistic locking without retries will have version conflicts on high contention
        assertThat(oResult.failed()).isGreaterThan(0);
    }

    private BenchmarkResult runConcurrentTransfers(
            UUID fromId, UUID toId, BigDecimal amount, int totalTransfers, boolean optimistic) throws Exception {

        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(totalTransfers);
        CountDownLatch readyLatch = new CountDownLatch(totalTransfers);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalTransfers; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    if (optimistic) {
                        transactionService.transferOptimistic(fromId, toId, amount);
                    } else {
                        transactionService.transfer(fromId, toId, amount);
                    }
                    success.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    failed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }));
        }

        readyLatch.await(30, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        return new BenchmarkResult(success.get(), failed.get());
    }

    private Account createAccount(String name, BigDecimal balance) {
        Account account = new Account();
        account.setOwnerName(name);
        account.setBalance(balance);
        return accountRepository.saveAndFlush(account);
    }

    private record BenchmarkResult(int success, int failed) {}
}


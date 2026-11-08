
package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PostgresLockingTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void secondTransactionMustWaitForFirstTransaction() throws Exception {

        Account account = new Account();
        account.setOwnerName("Lock Test");
        account.setBalance(new BigDecimal("1000.00"));

        account = accountRepository.saveAndFlush(account);

        UUID accountId = account.getId();

        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
        CountDownLatch secondLockAcquired = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(transactionManager);

        try {
            Future<?> first = executor.submit(() ->
                transactionTemplate.executeWithoutResult(status -> {

                    jdbcTemplate.queryForObject(
                        "SELECT id FROM account WHERE id = ? FOR UPDATE",
                        UUID.class,
                        accountId
                    );

                    firstLockAcquired.countDown();

                    try {
                        assertTrue(releaseFirstTransaction.await(
                            10, TimeUnit.SECONDS
                        ));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                })
            );

            assertTrue(firstLockAcquired.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() ->
                transactionTemplate.executeWithoutResult(status -> {

                    jdbcTemplate.queryForObject(
                        "SELECT id FROM account WHERE id = ? FOR UPDATE",
                        UUID.class,
                        accountId
                    );

                    secondLockAcquired.countDown();
                })
            );

            try {
                assertFalse(
                    secondLockAcquired.await(1, TimeUnit.SECONDS),
                    "Second transaction should be waiting for the row lock"
                );
            } finally {
                releaseFirstTransaction.countDown();
            }

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertEquals(
                0,
                secondLockAcquired.getCount(),
                "Second transaction should acquire the lock after the first commits"
            );

        } finally {
            releaseFirstTransaction.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}

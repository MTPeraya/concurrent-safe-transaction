
package com.mtperaya.transactionbackend.service;

// Entities
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.entity.TransactionStatus;

// Repositories
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;

// Custom exceptions
import com.mtperaya.transactionbackend.exception.AccountNotFoundException;
import com.mtperaya.transactionbackend.exception.InsufficientFundsException;
import com.mtperaya.transactionbackend.exception.InvalidTransferException;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;

// JUnit 5
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Spring Boot testing
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// Mockito
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

// Java
import java.math.BigDecimal;
import java.util.UUID;

// Assertions
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class TransactionServiceTest {

    // DEPENDENCIES

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @MockitoSpyBean
    private TransactionRepository transactionRepository;


    // TEST DATA

    private Account sender;
    private Account receiver;

    @BeforeEach
    void setUp() {

        // Create sender account
        sender = new Account();
        sender.setOwnerName("Alice");
        sender.setBalance(new BigDecimal("500.00"));
        sender = accountRepository.save(sender);

        // Create receiver account
        receiver = new Account();
        receiver.setOwnerName("Bob");
        receiver.setBalance(new BigDecimal("100.00"));
        receiver = accountRepository.save(receiver);
    }


    // 1. SUCCESSFUL TRANSFER

    @Test
    void shouldTransferMoneySuccessfully() {

        Transaction transaction = transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("150.00")
        );

        // Flush changes to database
        accountRepository.flush();

        // Verify persisted balances
        Account updatedSender = accountRepository.findById(sender.getId())
                .orElseThrow();

        Account updatedReceiver = accountRepository.findById(receiver.getId())
                .orElseThrow();

        assertEquals(0,
                new BigDecimal("350.00").compareTo(updatedSender.getBalance()));

        assertEquals(0,
                new BigDecimal("250.00").compareTo(updatedReceiver.getBalance()));

        // Verify transaction record
        Transaction savedTransaction = transactionRepository
                .findById(transaction.getId())
                .orElseThrow();

        assertEquals(TransactionStatus.COMPLETED, savedTransaction.getStatus());

        assertEquals(0,
                new BigDecimal("150.00").compareTo(savedTransaction.getAmount()));

        assertEquals(sender.getId(), savedTransaction.getFromAccountId());
        assertEquals(receiver.getId(), savedTransaction.getToAccountId());

        assertEquals(1, transactionRepository.count());
    }


    // 2. INSUFFICIENT FUNDS

    @Test
    void shouldRejectInsufficientFunds() {

        assertThrows(InsufficientFundsException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("600.00")
                )
        );

        assertEquals(0,
                new BigDecimal("500.00").compareTo(
                        accountRepository.findById(sender.getId())
                                .orElseThrow().getBalance()));

        assertEquals(0,
                new BigDecimal("100.00").compareTo(
                        accountRepository.findById(receiver.getId())
                                .orElseThrow().getBalance()));

        assertEquals(0, transactionRepository.count());
    }


    // 3. MISSING ACCOUNT TESTS
 
    @Test
    void shouldRejectMissingSender() {

        assertThrows(AccountNotFoundException.class, () ->
                transactionService.transfer(
                        UUID.randomUUID(),
                        receiver.getId(),
                        new BigDecimal("100.00")
                )
        );

        assertEquals(0, transactionRepository.count());
    }

    @Test
    void shouldRejectMissingReceiver() {

        assertThrows(AccountNotFoundException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        UUID.randomUUID(),
                        new BigDecimal("100.00")
                )
        );

        assertEquals(0,
                new BigDecimal("500.00").compareTo(
                        accountRepository.findById(sender.getId())
                                .orElseThrow().getBalance()));

        assertEquals(0, transactionRepository.count());
    }


    // 4. INVALID TRANSFER TESTS

    @Test
    void shouldRejectNullAmount() {

        assertThrows(InvalidTransferException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        null
                )
        );

        assertEquals(0,
                new BigDecimal("500.00").compareTo(
                        accountRepository.findById(sender.getId())
                                .orElseThrow().getBalance()));

        assertEquals(0,
                new BigDecimal("100.00").compareTo(
                        accountRepository.findById(receiver.getId())
                                .orElseThrow().getBalance()));

        assertEquals(0, transactionRepository.count());
    }

    @Test
    void shouldRejectZeroAmount() {

        assertThrows(InvalidTransferException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        BigDecimal.ZERO
                )
        );

        assertEquals(0, transactionRepository.count());
    }

    @Test
    void shouldRejectNegativeAmount() {

        assertThrows(InvalidTransferException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("-50.00")
                )
        );

        assertEquals(0, transactionRepository.count());
    }

    @Test
    void shouldRejectSameSenderAndReceiver() {

        assertThrows(InvalidTransferException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        sender.getId(),
                        new BigDecimal("100.00")
                )
        );

        assertEquals(0, transactionRepository.count());
    }


    // 5. ATOMIC ROLLBACK TEST

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRollbackBalancesWhenTransactionSaveFails() {

        // Simulate transaction persistence failure
        doAnswer(invocation -> {

            // Flush account updates before failure
            accountRepository.flush();

            throw new RuntimeException(
                    "Simulated transaction save failure");

        }).when(transactionRepository).save(any(Transaction.class));

        // Attempt transfer and expect failure
        assertThrows(RuntimeException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("150.00")
                )
        );

        // Retrieve accounts from database
        Account persistedSender = accountRepository.findById(sender.getId())
                .orElseThrow();

        Account persistedReceiver = accountRepository.findById(receiver.getId())
                .orElseThrow();

        // Verify original balances remain unchanged
        assertEquals(0,
                new BigDecimal("500.00")
                        .compareTo(persistedSender.getBalance()));

        assertEquals(0,
                new BigDecimal("100.00")
                        .compareTo(persistedReceiver.getBalance()));

        // Verify no transaction was saved
        assertEquals(0, transactionRepository.count());
    }
}

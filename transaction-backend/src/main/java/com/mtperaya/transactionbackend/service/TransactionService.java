package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.entity.TransactionStatus;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;
import com.mtperaya.transactionbackend.dto.TransactionHistoryResponse;
import com.mtperaya.transactionbackend.dto.TransferResult;

import com.mtperaya.transactionbackend.exception.InvalidTransferException;
import com.mtperaya.transactionbackend.exception.AccountNotFoundException;
import com.mtperaya.transactionbackend.exception.AccessForbiddenException;
import com.mtperaya.transactionbackend.exception.InsufficientFundsException;
import com.mtperaya.transactionbackend.exception.IdempotencyConflictException;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final EntityManager entityManager;

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            EntityManager entityManager) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entityManager = entityManager;
    }

    // Existing method retained for existing service/concurrency tests.
    @Transactional
    public Transaction transfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        validateTransfer(fromAccountId, toAccountId, amount);

        return performTransfer(
                fromAccountId,
                toAccountId,
                amount,
                UUID.randomUUID().toString()
        );
    }

    // New idempotent transfer method used by the API.
    // Returns a TransferResult that tells the controller whether this is a replay (200) or new (201).
    @Transactional
    public Transaction transfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String idempotencyKey) {

        return transfer(fromAccountId, toAccountId, amount, idempotencyKey, null).getTransaction();
    }

    @Transactional
    public TransferResult transfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String idempotencyKey,
            User requestingUser) {

        validateTransfer(fromAccountId, toAccountId, amount);
        validateIdempotencyKey(idempotencyKey);

        // Authorization: the sender account must belong to the requesting user (if provided).
        if (requestingUser != null) {
            Account senderAccount = accountRepository.findById(fromAccountId)
                    .orElseThrow(() -> new AccountNotFoundException(
                            "Account not found: " + fromAccountId));

            if (senderAccount.getOwner() == null || !senderAccount.getOwner().getId().equals(requestingUser.getId())) {
                throw new AccessForbiddenException(
                        "You do not have permission to transfer from this account");
            }
        }

        // 1. Acquire a PostgreSQL transaction-level advisory lock.
        entityManager.createNativeQuery("""
                SELECT 1
                FROM (
                SELECT pg_advisory_xact_lock(
                        hashtextextended(:idempotencyKey, 0)
                )
                ) AS lock_result
                """)
                .setParameter("idempotencyKey", idempotencyKey)
                .getSingleResult();

        // 2. Check whether this key has already been processed.
        Transaction existingTransaction = transactionRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);

        if (existingTransaction != null) {

            // 3. Make sure the new request has the original details.
            boolean sameRequest =
                    existingTransaction.getFromAccountId().equals(fromAccountId)
                    && existingTransaction.getToAccountId().equals(toAccountId)
                    && existingTransaction.getAmount().compareTo(amount) == 0;

            if (!sameRequest) {
                throw new IdempotencyConflictException(
                        "Idempotency key has already been used with different transfer details"
                );
            }

            // 4. Return the original transaction as a replay (200 OK).
            return new TransferResult(existingTransaction, true);
        }

        // 5. First use of this key: perform the transfer (201 Created).
        Transaction newTransaction = performTransfer(
                fromAccountId,
                toAccountId,
                amount,
                idempotencyKey
        );
        return new TransferResult(newTransaction, false);
    }

    private void validateTransfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException(
                    "Amount must be greater than zero"
            );
        }

        if (fromAccountId == null || toAccountId == null) {
            throw new InvalidTransferException(
                    "Account IDs cannot be null"
            );
        }

        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException(
                    "Cannot transfer to the same account"
            );
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidTransferException(
                    "Idempotency-Key header cannot be blank"
            );
        }

        if (idempotencyKey.length() > 255) {
            throw new InvalidTransferException(
                    "Idempotency-Key cannot exceed 255 characters"
            );
        }
    }

    private Transaction performTransfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            String idempotencyKey) {

        // 1. Determine consistent lock order (ascending UUID).
        UUID firstAccountId = fromAccountId.compareTo(toAccountId) < 0
                ? fromAccountId : toAccountId;

        UUID secondAccountId = fromAccountId.compareTo(toAccountId) < 0
                ? toAccountId : fromAccountId;

        // 2. Lock both accounts in the same order.
        Account firstAccount = accountRepository
                .findByIdWithLock(firstAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Account not found: " + firstAccountId
                        ));

        Account secondAccount = accountRepository
                .findByIdWithLock(secondAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Account not found: " + secondAccountId
                        ));

        // 3. Restore sender and receiver roles.
        Account sender = fromAccountId.equals(firstAccountId)
                ? firstAccount : secondAccount;

        Account receiver = toAccountId.equals(firstAccountId)
                ? firstAccount : secondAccount;

        // 4. Check sufficient funds.
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds"
            );
        }

        // 5. Update balances.
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        // 6. Create transaction record with its idempotency key.
        Transaction transaction = new Transaction(
                sender.getId(),
                receiver.getId(),
                amount,
                TransactionStatus.COMPLETED,
                idempotencyKey
        );

        return transactionRepository.save(transaction);
    }

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getAccountTransactionHistory(
            UUID accountId,
            Pageable pageable) {

        return getAccountTransactionHistory(accountId, pageable, null);
    }

    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getAccountTransactionHistory(
            UUID accountId,
            Pageable pageable,
            User requestingUser) {

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Account not found: " + accountId
                        ));

        if (requestingUser != null) {
            if (account.getOwner() == null || !account.getOwner().getId().equals(requestingUser.getId())) {
                throw new AccessForbiddenException(
                        "You do not have permission to view transaction history for this account");
            }
        }

        return transactionRepository
                .findAccountHistory(accountId, pageable)
                .map(transaction -> new TransactionHistoryResponse(
                        transaction.getId(),
                        transaction.getFromAccountId(),
                        transaction.getToAccountId(),
                        transaction.getAmount(),
                        transaction.getStatus(),
                        transaction.getCreatedAt()
                ));
    }
}
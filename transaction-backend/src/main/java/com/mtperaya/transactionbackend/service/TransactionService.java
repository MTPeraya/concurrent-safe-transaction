package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.repository.TransactionRepository;
import com.mtperaya.transactionbackend.entity.TransactionStatus;
import com.mtperaya.transactionbackend.dto.TransactionHistoryResponse;

import com.mtperaya.transactionbackend.exception.InvalidTransferException;
import com.mtperaya.transactionbackend.exception.AccountNotFoundException;
import com.mtperaya.transactionbackend.exception.InsufficientFundsException;


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

    public TransactionService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        }
        
    @Transactional
    public Transaction transfer(
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount) {

        // 1. Validate amount
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException(
                    "Amount must be greater than zero");
        }

        // 2. Prevent transfers to the same account
        if (fromAccountId == null || toAccountId == null) {
            throw new InvalidTransferException("Account IDs cannot be null");
        }

        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidTransferException(
                    "Cannot transfer to the same account");
        }

        // 3. Determine consistent lock order (ascending UUID)
        UUID firstAccountId = fromAccountId.compareTo(toAccountId) < 0
                ? fromAccountId : toAccountId;

        UUID secondAccountId = fromAccountId.compareTo(toAccountId) < 0
                ? toAccountId : fromAccountId;

        // 4. Lock both accounts in the same order
        
        Account firstAccount = accountRepository.findByIdWithLock(firstAccountId)
        // Account firstAccount = accountRepository.findById(firstAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException("Account not found: " + firstAccountId));

        Account secondAccount = accountRepository.findByIdWithLock(secondAccountId)
        // Account secondAccount = accountRepository.findById(secondAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException("Account not found: " + secondAccountId));

        // 6. Restore sender and receiver roles
        Account sender = fromAccountId.equals(firstAccountId)
                ? firstAccount : secondAccount;

        Account receiver = toAccountId.equals(firstAccountId)
                ? firstAccount : secondAccount;

        // 7. Check sufficient funds
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds");
        }

        // 8. Update balances using dirty checking
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        // 9. Create transaction record
        Transaction transaction = new Transaction(
                sender.getId(),
                receiver.getId(),
                amount,
                TransactionStatus.COMPLETED
        );

        // 10. Save and return transaction
        return transactionRepository.save(transaction);
    }
 
    @Transactional(readOnly = true)
    public Page<TransactionHistoryResponse> getAccountTransactionHistory(
        UUID accountId, Pageable pageable) {

        // Verify account exists
        accountRepository.findById(accountId)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Account not found: " + accountId
                        )
                );

        // Retrieve history and convert entities into DTOs
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


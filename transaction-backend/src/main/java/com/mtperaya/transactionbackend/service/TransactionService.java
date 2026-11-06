package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.repository.TransactionRepository;
import com.mtperaya.transactionbackend.entity.TransactionStatus;

import com.mtperaya.transactionbackend.exception.InvalidTransferException;
import com.mtperaya.transactionbackend.exception.AccountNotFoundException;
import com.mtperaya.transactionbackend.exception.InsufficientFundsException;


import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


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

        // 3. Retrieve sender
        Account sender = accountRepository.findById(fromAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException("Sender account not found"));

        // 4. Retrieve receiver
        Account receiver = accountRepository.findById(toAccountId)
                .orElseThrow(() ->
                        new AccountNotFoundException("Receiver account not found"));

        // 5. Check sufficient funds
        if (sender.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds");
        }

        // 6. Update balances using dirty checking
        sender.setBalance(sender.getBalance().subtract(amount));
        receiver.setBalance(receiver.getBalance().add(amount));

        // 7. Create transaction record
        Transaction transaction = new Transaction(
                sender.getId(),
                receiver.getId(),
                amount,
                TransactionStatus.COMPLETED
        );

        // 8. Save and return transaction
        return transactionRepository.save(transaction);
    }
}


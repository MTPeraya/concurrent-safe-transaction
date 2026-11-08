package com.mtperaya.transactionbackend.dto;

import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        TransactionStatus status,
        Instant createdAt
) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getFromAccountId(),
                transaction.getToAccountId(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
    }
}
package com.mtperaya.transactionbackend.repository;

import com.mtperaya.transactionbackend.entity.Transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository
        extends JpaRepository<Transaction, UUID> {

    Page<Transaction> findByFromAccountId(
            UUID accountId,
            Pageable pageable
    );

    Page<Transaction> findByToAccountId(
            UUID accountId,
            Pageable pageable
    );

    @Query("""
            SELECT t
            FROM Transaction t
            WHERE t.fromAccountId = :accountId
               OR t.toAccountId = :accountId
            """)
    Page<Transaction> findAccountHistory(
            @Param("accountId") UUID accountId,
            Pageable pageable
    );

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
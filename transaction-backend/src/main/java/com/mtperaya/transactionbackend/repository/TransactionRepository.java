
package com.mtperaya.transactionbackend.repository;

import com.mtperaya.transactionbackend.entity.Transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface TransactionRepository
        extends JpaRepository<Transaction, UUID> {

    // 1. Find transactions sent by an account
    Page<Transaction> findByFromAccountId(
            UUID accountId,
            Pageable pageable
    );

    // 2. Find transactions received by an account
    Page<Transaction> findByToAccountId(
            UUID accountId,
            Pageable pageable
    );

    // 3. Find all transactions involving an account
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
}

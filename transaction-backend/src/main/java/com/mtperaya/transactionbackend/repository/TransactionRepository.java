package com.mtperaya.transactionbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import com.mtperaya.transactionbackend.entity.Transaction;

@Repository
public interface TransactionRepository
        extends JpaRepository<Transaction, UUID> {
}

package com.mtperaya.transactionbackend.repository;

import com.mtperaya.transactionbackend.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
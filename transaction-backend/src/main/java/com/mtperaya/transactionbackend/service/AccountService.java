package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.repository.AccountRepository;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.UUID;

@Service // @Service tells Spring: Create and manage an instance of this class for me.
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }
    
    public Account createAccount(String ownerName, BigDecimal initialBalance) {
        // 1. Create Account object
        Account account = new Account();

        // 2. Set ownerName
        account.setOwnerName(ownerName);

        // 3. Set initial balance
        account.setBalance(initialBalance);

        // 4. Save through repository + 5. Return saved account
        return accountRepository.save(account);
    }

    public Optional<Account> getAccount(UUID id) {
        return accountRepository.findById(id);
    }

}

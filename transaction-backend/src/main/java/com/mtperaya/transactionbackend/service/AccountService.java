package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.exception.AccessForbiddenException;
import com.mtperaya.transactionbackend.repository.AccountRepository;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service // @Service tells Spring: Create and manage an instance of this class for me.
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account createAccount(String ownerName, BigDecimal initialBalance, User owner) {
        // 1. Create Account object
        Account account = new Account();

        // 2. Set ownerName
        account.setOwnerName(ownerName);

        // 3. Set initial balance
        account.setBalance(initialBalance);

        // 4. Associate account with the authenticated user
        account.setOwner(owner);

        // 5. Save through repository + 6. Return saved account
        return accountRepository.save(account);
    }

    public Optional<Account> getAccount(UUID id) {
        return accountRepository.findById(id);
    }

    /**
     * Retrieves an account and enforces that it belongs to the given user.
     * Throws AccessForbiddenException (403) if the account exists but is owned by someone else.
     */
    public Account getAccountForUser(UUID id, User requestingUser) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new com.mtperaya.transactionbackend.exception.AccountNotFoundException(
                        "Account not found: " + id));

        if (requestingUser != null) {
            if (account.getOwner() == null || !account.getOwner().getId().equals(requestingUser.getId())) {
                throw new AccessForbiddenException(
                        "You do not have access to this account");
            }
        }

        return account;
    }

    public List<Account> getAccountsByOwner(User owner) {
        if (owner == null) {
            return Collections.emptyList();
        }
        return accountRepository.findByOwner(owner);
    }

}

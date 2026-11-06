package com.mtperaya.transactionbackend.exception;

// When to throw: Sender or receiver account doesn't exist
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String message) {
        super(message);
    }
}
package com.mtperaya.transactionbackend.exception;

// When to throw: Sender doesn't have enough money
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
package com.mtperaya.transactionbackend.exception;

// When to throw: Amount is null, zero, negative, or sender and receiver are identical
public class InvalidTransferException extends RuntimeException {

    public InvalidTransferException(String message) {
        super(message);
    }
}
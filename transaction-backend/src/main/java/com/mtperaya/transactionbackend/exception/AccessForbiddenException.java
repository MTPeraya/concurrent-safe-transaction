package com.mtperaya.transactionbackend.exception;

// Thrown when the authenticated user attempts to access a resource owned by another user.
public class AccessForbiddenException extends RuntimeException {

    public AccessForbiddenException(String message) {
        super(message);
    }
}

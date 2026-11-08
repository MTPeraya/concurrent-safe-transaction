package com.mtperaya.transactionbackend.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.MissingRequestHeaderException;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, Object> handleValidationException(
            MethodArgumentNotValidException exception) {

        Map<String, String> errors = new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                    errors.put(
                        error.getField(),
                        error.getDefaultMessage()
                    )
                );

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 400);
        response.put("message", "Validation failed");
        response.put("errors", errors);

        return response;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public Map<String, Object> handleAccountNotFound(
            AccountNotFoundException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 404);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(InvalidPaginationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, Object> handleInvalidPagination(
            InvalidPaginationException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 400);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @ResponseBody
    public Map<String, Object> handleEmailAlreadyRegistered(
            EmailAlreadyRegisteredException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 409);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    @ResponseBody
    public Map<String, Object> handleIdempotencyConflict(
            IdempotencyConflictException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 409);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    @ResponseBody
    public Map<String, Object> handleInsufficientFunds(
            InsufficientFundsException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 422);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(InvalidTransferException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, Object> handleInvalidTransfer(
            InvalidTransferException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 400);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public Map<String, Object> handleMissingRequestHeader(
            MissingRequestHeaderException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 400);
        response.put("message", "Required header is missing: "
                + exception.getHeaderName());

        return response;
    }
    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public Map<String, Object> handleInvalidCredentials(
            InvalidCredentialsException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 401);
        response.put("message", exception.getMessage());

        return response;
    }

    @ExceptionHandler(AccessForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ResponseBody
    public Map<String, Object> handleAccessForbidden(
            AccessForbiddenException exception) {

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", 403);
        response.put("message", exception.getMessage());

        return response;
    }
}

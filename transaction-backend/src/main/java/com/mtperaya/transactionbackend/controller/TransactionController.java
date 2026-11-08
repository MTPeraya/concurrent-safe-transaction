package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.dto.CreateTransactionRequest;
import com.mtperaya.transactionbackend.dto.TransactionResponse;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {

        Transaction transaction = transactionService.transfer(
                request.getFromAccountId(),
                request.getToAccountId(),
                request.getAmount(),
                idempotencyKey
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(TransactionResponse.from(transaction));
    }
}
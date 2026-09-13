package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.dto.CreateTransactionRequest;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transactions")
public class TransactionValidationController {

    @PostMapping("/validate")
    public ResponseEntity<Void> validateTransaction(
            @Valid @RequestBody CreateTransactionRequest request) {

        return ResponseEntity.ok().build();
    }
}

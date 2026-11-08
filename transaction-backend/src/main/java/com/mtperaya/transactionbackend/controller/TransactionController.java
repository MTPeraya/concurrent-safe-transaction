package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.dto.CreateTransactionRequest;
import com.mtperaya.transactionbackend.dto.TransactionResponse;
import com.mtperaya.transactionbackend.dto.TransferResult;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.repository.UserRepository;
import com.mtperaya.transactionbackend.service.TransactionService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final UserRepository userRepository;

    public TransactionController(
            TransactionService transactionService,
            UserRepository userRepository) {
        this.transactionService = transactionService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {

        User requestingUser = resolveUser(jwt);

        TransferResult result = transactionService.transfer(
                request.getFromAccountId(),
                request.getToAccountId(),
                request.getAmount(),
                idempotencyKey,
                requestingUser
        );

        // 200 OK for idempotency replay, 201 Created for a new transfer.
        HttpStatus status = result.isReplay() ? HttpStatus.OK : HttpStatus.CREATED;

        return ResponseEntity
                .status(status)
                .body(TransactionResponse.from(result.getTransaction()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private User resolveUser(Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found in database"));
    }
}
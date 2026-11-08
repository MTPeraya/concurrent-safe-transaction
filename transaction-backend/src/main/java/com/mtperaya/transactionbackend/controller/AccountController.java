package com.mtperaya.transactionbackend.controller;

import java.util.UUID;

import com.mtperaya.transactionbackend.dto.CreateAccountRequest;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.repository.UserRepository;
import com.mtperaya.transactionbackend.service.AccountService;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import jakarta.validation.Valid;


@RestController // tells Spring that this class is a controller that will eventually handle HTTP requests.
@RequestMapping("/accounts") 
public class AccountController {

    private final AccountService accountService;
    private final UserRepository userRepository;

    public AccountController(AccountService accountService, UserRepository userRepository) {
        this.accountService = accountService;
        this.userRepository = userRepository;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CreateAccountRequest request) {

        User owner = resolveUser(jwt);

        Account account = accountService.createAccount(
            request.getOwnerName(),
            request.getInitialBalance(),
            owner
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(account);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccount(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id) {

        User requestingUser = resolveUser(jwt);

        // getAccountForUser throws AccountNotFoundException (404) or
        // AccessForbiddenException (403) as appropriate.
        Account account = accountService.getAccountForUser(id, requestingUser);

        return ResponseEntity.ok(account);
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

// note: The controller is not creating the account itself
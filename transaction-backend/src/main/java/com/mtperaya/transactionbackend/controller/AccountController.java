package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.dto.CreateAccountRequest;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.service.AccountService;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;


@RestController // tells Spring that this class is a controller that will eventually handle HTTP requests.
@RequestMapping("/accounts") 
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(
            request.getOwnerName(),
            request.getInitialBalance()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(account);
    }

}

// note: The controller is not creating the account itself
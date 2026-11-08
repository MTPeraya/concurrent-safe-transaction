package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.dto.PageResponse;
import com.mtperaya.transactionbackend.dto.TransactionHistoryResponse;
import com.mtperaya.transactionbackend.exception.InvalidPaginationException;
import com.mtperaya.transactionbackend.service.TransactionService;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/accounts/{accountId}/transactions")
public class AccountTransactionHistoryController {

    private final TransactionService transactionService;

    public AccountTransactionHistoryController(
            TransactionService transactionService) {

        this.transactionService = transactionService;
    }

    @GetMapping
    public PageResponse<TransactionHistoryResponse> getTransactionHistory(
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        // Validate page number
        if (page < 0) {
            throw new InvalidPaginationException(
                    "Page number cannot be negative"
            );
        }

        // Validate page size
        if (size < 1 || size > 100) {
            throw new InvalidPaginationException(
                    "Page size must be between 1 and 100"
            );
        }

        // Newest transactions first
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        return PageResponse.from(
                transactionService.getAccountTransactionHistory(
                        accountId,
                        pageable
                )
        );
    }
}

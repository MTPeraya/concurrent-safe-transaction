package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.dto.AuditRecordResponse;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.exception.AccessForbiddenException;
import com.mtperaya.transactionbackend.exception.AccountNotFoundException;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AuditQueryService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final CassandraAuditClient cassandraAuditClient;

    public AuditQueryService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            @org.springframework.beans.factory.annotation.Autowired(required = false) CassandraAuditClient cassandraAuditClient) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.cassandraAuditClient = cassandraAuditClient;
    }

    @Transactional(readOnly = true)
    public List<AuditRecordResponse> getAuditTrail(UUID accountId, int limit, User requestingUser) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found: " + accountId));

        if (requestingUser != null) {
            if (account.getOwner() == null || !account.getOwner().getId().equals(requestingUser.getId())) {
                throw new AccessForbiddenException("You do not have access to this account");
            }
        }

        // Try Cassandra audit store first (FR-AUD-2)
        if (cassandraAuditClient != null && cassandraAuditClient.isConnected()) {
            List<AuditRecordResponse> cassandraRecords = cassandraAuditClient.queryAuditTrail(accountId, limit);
            if (cassandraRecords != null) {
                return cassandraRecords;
            }
        }

        // Fallback to transactional Postgres ledger
        PageRequest pageRequest = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return transactionRepository.findAccountHistory(accountId, pageRequest)
                .stream()
                .map(tx -> toAuditRecordResponse(accountId, tx))
                .toList();
    }

    private AuditRecordResponse toAuditRecordResponse(UUID accountId, Transaction tx) {
        boolean isDebit = accountId.equals(tx.getFromAccountId());
        UUID counterparty = isDebit ? tx.getToAccountId() : tx.getFromAccountId();
        String direction = isDebit ? "DEBIT" : "CREDIT";

        return new AuditRecordResponse(
                accountId,
                tx.getCreatedAt(),
                UUID.randomUUID(), // mapped to eventId
                tx.getId(),
                counterparty,
                direction,
                tx.getAmount(),
                "DEMO",
                UUID.randomUUID()
        );
    }
}

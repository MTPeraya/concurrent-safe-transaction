
package com.mtperaya.transactionbackend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.Transaction;
import com.mtperaya.transactionbackend.entity.TransactionStatus;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class TransactionHistoryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private Account alice;
    private Account bob;
    private Account charlie;
    

    @BeforeEach
    void setUp() {

        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        alice = createAccount("Alice", "1000.00");
        bob = createAccount("Bob", "1000.00");
        charlie = createAccount("Charlie", "1000.00");
    }

    private Account createAccount(String name, String balance) {

        Account account = new Account();

        account.setOwnerName(name);
        account.setBalance(new BigDecimal(balance));

        return accountRepository.saveAndFlush(account);
    }

    private Transaction createTransaction(
            Account sender,
            Account receiver,
            String amount) {
                

        Transaction transaction = new Transaction(
                sender.getId(),
                receiver.getId(),
                new BigDecimal(amount),
                TransactionStatus.COMPLETED
        );

        return transactionRepository.saveAndFlush(transaction);
    }

    private void setCreatedAt(
            Transaction transaction,
            Instant timestamp) {

        jdbcTemplate.update(
                "UPDATE transactions SET created_at = ? WHERE id = ?",
                Timestamp.from(timestamp),
                transaction.getId()
        );
    }

    // 1. SENT TRANSACTIONS

    @Test
    void shouldReturnSentTransactions() throws Exception {

        createTransaction(alice, bob, "100.00");
        createTransaction(alice, charlie, "200.00");

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
    }

    // 2. RECEIVED TRANSACTIONS

    @Test
    void shouldReturnReceivedTransactions() throws Exception {

        createTransaction(bob, alice, "100.00");
        createTransaction(charlie, alice, "200.00");

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(2));
    }

    // 3. SENT AND RECEIVED TRANSACTIONS

    @Test
    void shouldReturnBothSentAndReceivedTransactions()
            throws Exception {

        createTransaction(alice, bob, "100.00");
        createTransaction(bob, alice, "200.00");
        createTransaction(alice, charlie, "50.00");

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(3))
        .andExpect(jsonPath("$.totalElements").value(3));
    }

    // 4. EMPTY HISTORY

    @Test
    void shouldReturnEmptyPageWhenNoTransactionsExist()
            throws Exception {

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0))
        .andExpect(jsonPath("$.totalElements").value(0))
        .andExpect(jsonPath("$.totalPages").value(0));
    }

    // 5. ACCOUNT DOES NOT EXIST

    @Test
    void shouldReturn404WhenAccountDoesNotExist()
            throws Exception {

        UUID unknownId = UUID.randomUUID();

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                unknownId
        ))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404));
    }

    // 6. PAGINATION

    @Test
    void shouldReturnCorrectPagination() throws Exception {

        createTransaction(alice, bob, "10.00");
        createTransaction(alice, bob, "20.00");
        createTransaction(alice, bob, "30.00");

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        )
        .param("page", "0")
        .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.first").value(true))
        .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        )
        .param("page", "1")
        .param("size", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.last").value(true));
    }

    // 7. INVALID PAGE SIZE

    @Test
    void shouldRejectZeroPageSize() throws Exception {

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ).param("size", "0"))
        .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectPageSizeAbove100() throws Exception {

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ).param("size", "101"))
        .andExpect(status().isBadRequest());
    }

    // 8. INVALID PAGE NUMBER

    @Test
    void shouldRejectNegativePageNumber() throws Exception {

        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ).param("page", "-1"))
        .andExpect(status().isBadRequest());
    }

    // 9. SORTING BY CREATION TIME

    @Test
    void shouldReturnNewestTransactionsFirst() throws Exception {

        Transaction older = createTransaction(
                alice, bob, "100.00"
        );

        Transaction newer = createTransaction(
                alice, bob, "200.00"
        );

        setCreatedAt(
                older,
                Instant.parse("2025-01-01T10:00:00Z")
        );

        setCreatedAt(
                newer,
                Instant.parse("2025-01-02T10:00:00Z")
        );

        String response = mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();

        JsonNode content = objectMapper.readTree(response)
                .get("content");

        assertEquals(
                newer.getId().toString(),
                content.get(0).get("id").asText()
        );

        assertEquals(
                older.getId().toString(),
                content.get(1).get("id").asText()
        );
    }

    // 10. HISTORY MUST NOT MODIFY AUDIT RECORDS

    @Test
    void shouldNotModifyTransactionRecords() throws Exception {

        Transaction transaction = createTransaction(
                alice, bob, "150.00"
        );

        // Capture original values
        UUID originalId = transaction.getId();
        UUID originalSender = transaction.getFromAccountId();
        UUID originalReceiver = transaction.getToAccountId();
        BigDecimal originalAmount = transaction.getAmount();
        TransactionStatus originalStatus = transaction.getStatus();
        Instant originalTimestamp = transaction.getCreatedAt();

        // Retrieve transaction history
        mockMvc.perform(get(
                "/accounts/{id}/transactions",
                alice.getId()
        ))
        .andExpect(status().isOk());

        // Retrieve the persisted transaction again
        Transaction persisted = transactionRepository
                .findById(originalId)
                .orElseThrow();

        // Verify every audit field remains unchanged
        assertEquals(originalSender, persisted.getFromAccountId());
        assertEquals(originalReceiver, persisted.getToAccountId());

        assertEquals(
                0,
                originalAmount.compareTo(persisted.getAmount())
        );

        assertEquals(originalStatus, persisted.getStatus());

        assertEquals(
            originalTimestamp.truncatedTo(java.time.temporal.ChronoUnit.MICROS), 
            persisted.getCreatedAt()
);
    }
}

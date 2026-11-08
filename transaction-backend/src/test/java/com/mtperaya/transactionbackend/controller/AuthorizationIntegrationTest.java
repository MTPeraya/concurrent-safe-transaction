package com.mtperaya.transactionbackend.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.dto.CreateAccountRequest;
import com.mtperaya.transactionbackend.dto.CreateTransactionRequest;
import com.mtperaya.transactionbackend.dto.LoginRequest;
import com.mtperaya.transactionbackend.dto.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class AuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String registerAndLogin(String email, String password) throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setEmail(email);
        registerRequest.setPassword(password);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail(email);
        loginRequest.setPassword(password);

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return responseNode.get("accessToken").asString();
    }

    private String createAccountForUser(String token, String ownerName, BigDecimal balance) throws Exception {
        CreateAccountRequest request = new CreateAccountRequest();
        request.setOwnerName(ownerName);
        request.setInitialBalance(balance);

        MvcResult result = mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode responseNode = objectMapper.readTree(result.getResponse().getContentAsString());
        return responseNode.get("id").asString();
    }

    @Test
    @DisplayName("User should be able to view their own account details")
    void shouldAllowUserToViewOwnAccount() throws Exception {
        String token = registerAndLogin("user1@example.com", "Password123!");
        String accountId = createAccountForUser(token, "User One Account", new BigDecimal("500.00"));

        mockMvc.perform(get("/accounts/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.balance").value(500.00))
                .andExpect(jsonPath("$.ownerName").value("User One Account"));
    }

    @Test
    @DisplayName("User should be forbidden (403) from viewing another user's account")
    void shouldForbidUserFromViewingAnotherUsersAccount() throws Exception {
        String tokenUserA = registerAndLogin("alice_own@example.com", "Password123!");
        String tokenUserB = registerAndLogin("bob_own@example.com", "Password123!");

        String accountA = createAccountForUser(tokenUserA, "Alice Account", new BigDecimal("500.00"));

        // User B tries to view User A's account
        mockMvc.perform(get("/accounts/" + accountA)
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You do not have access to this account"));
    }

    @Test
    @DisplayName("User should be able to transfer money from their own account to another user's account")
    void shouldAllowTransferFromOwnAccountToAnotherUserAccount() throws Exception {
        String tokenUserA = registerAndLogin("alice_transfer@example.com", "Password123!");
        String tokenUserB = registerAndLogin("bob_transfer@example.com", "Password123!");

        String accountA = createAccountForUser(tokenUserA, "Alice Sender", new BigDecimal("1000.00"));
        String accountB = createAccountForUser(tokenUserB, "Bob Receiver", new BigDecimal("200.00"));

        CreateTransactionRequest transferRequest = new CreateTransactionRequest();
        transferRequest.setFromAccountId(UUID.fromString(accountA));
        transferRequest.setToAccountId(UUID.fromString(accountB));
        transferRequest.setAmount(new BigDecimal("150.00"));

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromAccountId").value(accountA))
                .andExpect(jsonPath("$.toAccountId").value(accountB))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("User should be forbidden (403) from transferring money from an account they do not own")
    void shouldForbidTransferFromAccountNotOwned() throws Exception {
        String tokenUserA = registerAndLogin("victim@example.com", "Password123!");
        String tokenUserB = registerAndLogin("attacker@example.com", "Password123!");

        String accountA = createAccountForUser(tokenUserA, "Victim Account", new BigDecimal("1000.00"));
        String accountB = createAccountForUser(tokenUserB, "Attacker Account", new BigDecimal("50.00"));

        // User B attempts to transfer money OUT OF User A's account
        CreateTransactionRequest unauthorizedRequest = new CreateTransactionRequest();
        unauthorizedRequest.setFromAccountId(UUID.fromString(accountA));
        unauthorizedRequest.setToAccountId(UUID.fromString(accountB));
        unauthorizedRequest.setAmount(new BigDecimal("300.00"));

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + tokenUserB)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(unauthorizedRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You do not have permission to transfer from this account"));
    }

    @Test
    @DisplayName("User should be able to view transaction history of their own account")
    void shouldAllowUserToViewOwnTransactionHistory() throws Exception {
        String tokenUserA = registerAndLogin("alice_hist@example.com", "Password123!");
        String tokenUserB = registerAndLogin("bob_hist@example.com", "Password123!");

        String accountA = createAccountForUser(tokenUserA, "Alice Hist", new BigDecimal("1000.00"));
        String accountB = createAccountForUser(tokenUserB, "Bob Hist", new BigDecimal("200.00"));

        CreateTransactionRequest transferRequest = new CreateTransactionRequest();
        transferRequest.setFromAccountId(UUID.fromString(accountA));
        transferRequest.setToAccountId(UUID.fromString(accountB));
        transferRequest.setAmount(new BigDecimal("100.00"));

        mockMvc.perform(post("/transactions")
                        .header("Authorization", "Bearer " + tokenUserA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transferRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/accounts/" + accountA + "/transactions")
                        .header("Authorization", "Bearer " + tokenUserA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("User should be forbidden (403) from viewing another user's transaction history")
    void shouldForbidUserFromViewingAnotherUsersTransactionHistory() throws Exception {
        String tokenUserA = registerAndLogin("alice_secret@example.com", "Password123!");
        String tokenUserB = registerAndLogin("snooper@example.com", "Password123!");

        String accountA = createAccountForUser(tokenUserA, "Alice Secret", new BigDecimal("1000.00"));

        mockMvc.perform(get("/accounts/" + accountA + "/transactions")
                        .header("Authorization", "Bearer " + tokenUserB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You do not have permission to view transaction history for this account"));
    }
}


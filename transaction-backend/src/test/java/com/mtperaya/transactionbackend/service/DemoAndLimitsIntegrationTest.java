package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.dto.LoginResponse;
import com.mtperaya.transactionbackend.dto.RegisterRequest;
import com.mtperaya.transactionbackend.dto.UserResponse;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.exception.AccountLimitExceededException;
import com.mtperaya.transactionbackend.exception.InvalidTransferException;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = {
        "app.account.max-per-user=3",
        "app.transfer.max-amount=5000.00",
        "app.transfer.max-daily-count=5"
})
@Transactional
class DemoAndLimitsIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("FR-ACC-1: Registration automatically provisions 1 demo account with $1,000.00")
    void shouldProvisionDemoAccountOnRegistration() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("demo-test@example.com");
        request.setPassword("Password123!");

        UserResponse userResponse = authService.register(request);
        User user = userRepository.findById(userResponse.getId()).orElseThrow();

        List<Account> accounts = accountService.getAccountsByOwner(user);
        assertEquals(1, accounts.size(), "Should have exactly 1 automatically provisioned account");
        assertEquals(0, new BigDecimal("1000.00").compareTo(accounts.get(0).getBalance()),
                "Provisioned account must have $1,000.00 starting balance");
    }

    @Test
    @DisplayName("FR-AUTH-4: One-click demo login creates throwaway user and returns valid token")
    void shouldCreateDemoUserAndLogin() {
        LoginResponse response = authService.createDemoUserAndLogin();
        assertNotNull(response);
        assertNotNull(response.getAccessToken());
        assertFalse(response.getAccessToken().isBlank());

        List<User> users = userRepository.findAll();
        assertEquals(1, users.size());
        assertTrue(users.get(0).getEmail().startsWith("demo-"));

        List<Account> accounts = accountService.getAccountsByOwner(users.get(0));
        assertEquals(1, accounts.size());
        assertEquals(0, new BigDecimal("1000.00").compareTo(accounts.get(0).getBalance()));
    }

    @Test
    @DisplayName("FR-ACC-3: Enforce account cap per user (max 3)")
    void shouldEnforceAccountCapPerUser() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("cap-test@example.com");
        request.setPassword("Password123!");
        UserResponse userResponse = authService.register(request);
        User user = userRepository.findById(userResponse.getId()).orElseThrow();

        // User already has 1 account from registration. Create account 2 and 3:
        accountService.createAccount("Second Account", new BigDecimal("100.00"), user);
        accountService.createAccount("Third Account", new BigDecimal("100.00"), user);

        assertEquals(3, accountService.getAccountsByOwner(user).size());

        // Attempting to create a 4th account must throw AccountLimitExceededException
        assertThrows(AccountLimitExceededException.class, () ->
                accountService.createAccount("Fourth Account", new BigDecimal("100.00"), user)
        );
    }

    @Test
    @DisplayName("FR-TRF-8: Reject transfer exceeding maximum transfer amount")
    void shouldRejectTransferExceedingAmountCap() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("transfer-cap@example.com");
        request.setPassword("Password123!");
        UserResponse userResponse = authService.register(request);
        User user = userRepository.findById(userResponse.getId()).orElseThrow();

        Account sender = accountService.getAccountsByOwner(user).get(0);
        Account receiver = accountService.createAccount("Receiver", new BigDecimal("0.00"), user);

        // Cap is configured to 5000.00, attempt 5001.00
        assertThrows(InvalidTransferException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("5001.00"),
                        "cap-key-1",
                        user
                )
        );
    }

    @Test
    @DisplayName("FR-TRF-8: Enforce daily transfer count per user")
    void shouldEnforceDailyTransferCountLimit() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("daily-cap@example.com");
        request.setPassword("Password123!");
        UserResponse userResponse = authService.register(request);
        User user = userRepository.findById(userResponse.getId()).orElseThrow();

        Account sender = accountService.getAccountsByOwner(user).get(0);
        Account receiver = accountService.createAccount("Receiver", new BigDecimal("0.00"), user);

        // Cap is 5 transfers per day. Execute 5 valid transfers:
        for (int i = 1; i <= 5; i++) {
            transactionService.transfer(
                    sender.getId(),
                    receiver.getId(),
                    new BigDecimal("10.00"),
                    "daily-key-" + i,
                    user
            );
        }

        // 6th transfer must be rejected
        InvalidTransferException ex = assertThrows(InvalidTransferException.class, () ->
                transactionService.transfer(
                        sender.getId(),
                        receiver.getId(),
                        new BigDecimal("10.00"),
                        "daily-key-6",
                        user
                )
        );
        assertTrue(ex.getMessage().contains("Daily transfer limit"), "Error message should mention daily limit");
    }
}

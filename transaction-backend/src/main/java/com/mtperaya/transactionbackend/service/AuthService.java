package com.mtperaya.transactionbackend.service;

import com.mtperaya.transactionbackend.dto.LoginRequest;
import com.mtperaya.transactionbackend.dto.LoginResponse;
import com.mtperaya.transactionbackend.dto.RegisterRequest;
import com.mtperaya.transactionbackend.dto.UserResponse;
import com.mtperaya.transactionbackend.entity.User;
import com.mtperaya.transactionbackend.exception.EmailAlreadyRegisteredException;
import com.mtperaya.transactionbackend.exception.InvalidCredentialsException;
import com.mtperaya.transactionbackend.repository.UserRepository;


import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    public static final BigDecimal DEMO_STARTING_BALANCE = new BigDecimal("1000.00");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AccountService accountService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtEncoder jwtEncoder,
        AccountService accountService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.accountService = accountService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {

        // Normalize email
        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        // Check whether email already exists
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new EmailAlreadyRegisteredException(
                    "Email is already registered"
            );
        }

        // Create new user
        User user = new User();
        user.setEmail(email);

        // Hash password before saving
        user.setPasswordHash(
                passwordEncoder.encode(request.getPassword())
        );

        // Save user
        User savedUser = userRepository.save(user);

        // FR-ACC-1: Automatically create demo account with fixed starting balance
        accountService.createAccount("Demo Account", DEMO_STARTING_BALANCE, savedUser);

        // Return safe response
        return new UserResponse(savedUser);
    }

    @Transactional
    public LoginResponse createDemoUserAndLogin() {
        String randomSuffix = UUID.randomUUID().toString().substring(0, 8);
        String demoEmail = "demo-" + randomSuffix + "@demo.local";
        String rawPassword = UUID.randomUUID().toString();

        User user = new User();
        user.setEmail(demoEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        User savedUser = userRepository.save(user);

        // FR-ACC-1: Provision demo account
        accountService.createAccount("Demo Account", DEMO_STARTING_BALANCE, savedUser);

        return generateTokenResponse(savedUser);
    }

    public LoginResponse login(LoginRequest request) {

        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new InvalidCredentialsException(
                        "Invalid email or password"
                ));

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPasswordHash()
        )) {
            throw new InvalidCredentialsException(
                    "Invalid email or password"
            );
        }

        return generateTokenResponse(user);
    }

    private LoginResponse generateTokenResponse(User user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("transaction-backend")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();

        return new LoginResponse(token);
    }
}
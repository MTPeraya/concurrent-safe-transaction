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

import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtEncoder jwtEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
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

        // Return safe response
        return new UserResponse(savedUser);
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
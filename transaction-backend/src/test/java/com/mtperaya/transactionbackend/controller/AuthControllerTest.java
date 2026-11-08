package com.mtperaya.transactionbackend.controller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.dto.LoginRequest;
import com.mtperaya.transactionbackend.dto.RegisterRequest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Test
    @DisplayName("Should register a new user successfully")
    void shouldRegisterUserSuccessfully() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("alice@example.com");
        request.setPassword("Password123!");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("Should reject registration with duplicate email")
    void shouldRejectDuplicateEmailRegistration() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("duplicate@example.com");
        request.setPassword("Password123!");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    @DisplayName("Should login with valid credentials and return a signed JWT with expected claims")
    void shouldLoginWithValidCredentialsAndReturnJwt() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setEmail("bob@example.com");
        registerRequest.setPassword("Password123!");

        MvcResult registerResult = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode registeredUser = objectMapper.readTree(registerResult.getResponse().getContentAsString());
        String expectedUserId = registeredUser.get("id").asString();

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("bob@example.com");
        loginRequest.setPassword("Password123!");

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();

        JsonNode loginResponse = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginResponse.get("accessToken").asString();

        SignedJWT signedJWT = SignedJWT.parse(token);
        assertThat(signedJWT.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(signedJWT.getJWTClaimsSet().getIssuer()).isEqualTo("transaction-backend");
        assertThat(signedJWT.getJWTClaimsSet().getSubject()).isEqualTo(expectedUserId);
        assertThat(signedJWT.getJWTClaimsSet().getStringClaim("email")).isEqualTo("bob@example.com");
        assertThat(signedJWT.getJWTClaimsSet().getExpirationTime()).isAfter(new Date());
    }

    @Test
    @DisplayName("Should reject login with invalid password")
    void shouldRejectLoginWithInvalidPassword() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setEmail("charlie@example.com");
        registerRequest.setPassword("CorrectPassword123!");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("charlie@example.com");
        loginRequest.setPassword("WrongPassword!");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Should reject login with non-existent email")
    void shouldRejectLoginWithNonExistentEmail() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("ghost@example.com");
        loginRequest.setPassword("Password123!");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    @DisplayName("Should allow access to protected endpoint with valid JWT")
    void shouldAccessProtectedEndpointWithValidJwt() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setEmail("david@example.com");
        registerRequest.setPassword("Password123!");

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("david@example.com");
        loginRequest.setPassword("Password123!");

        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("accessToken").asString();

        String createAccountPayload = """
                {
                    "ownerName": "David Checking",
                    "initialBalance": 500.00
                }
                """;

        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createAccountPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.balance").value(500.00));
    }

    @Test
    @DisplayName("Should reject protected endpoint when Authorization header is missing")
    void shouldRejectProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"No Token\",\"initialBalance\":100.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should reject protected endpoint when token is malformed")
    void shouldRejectProtectedEndpointWithInvalidToken() throws Exception {
        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer definitely.not.a.valid.jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Malformed Token\",\"initialBalance\":100.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should reject protected endpoint when token is expired")
    void shouldRejectProtectedEndpointWithExpiredToken() throws Exception {
        byte[] secretBytes = Base64.getDecoder().decode(jwtSecret);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .issuer("transaction-backend")
                .subject(UUID.randomUUID().toString())
                .claim("email", "expired@example.com")
                .issueTime(new Date(System.currentTimeMillis() - 1000 * 60 * 60))
                .expirationTime(new Date(System.currentTimeMillis() - 1000 * 60 * 10)) // Expired 10m ago
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
        signedJWT.sign(new MACSigner(secretBytes));

        String expiredToken = signedJWT.serialize();

        mockMvc.perform(post("/accounts")
                        .header("Authorization", "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Expired Token\",\"initialBalance\":100.00}"))
                .andExpect(status().isUnauthorized());
    }
}

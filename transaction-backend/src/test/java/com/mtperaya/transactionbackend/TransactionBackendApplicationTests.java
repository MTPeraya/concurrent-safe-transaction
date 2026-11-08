package com.mtperaya.transactionbackend;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class TransactionBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}



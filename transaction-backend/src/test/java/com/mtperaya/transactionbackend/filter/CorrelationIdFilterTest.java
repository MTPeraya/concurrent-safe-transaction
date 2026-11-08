package com.mtperaya.transactionbackend.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CorrelationIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should generate a new X-Request-Id response header if none is provided in request")
    void shouldGenerateRequestIdWhenNoneProvided() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/register"))
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("Should echo the provided X-Request-Id header in the response")
    void shouldEchoProvidedRequestId() throws Exception {
        String customId = "req-custom-trace-12345";

        mockMvc.perform(get("/auth/register")
                        .header("X-Request-Id", customId))
                .andExpect(header().string("X-Request-Id", customId));
    }
}


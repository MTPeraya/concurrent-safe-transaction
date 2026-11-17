package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusControllerTest {

    @Test
    @DisplayName("FR-DEMO-6: Status endpoint returns healthy operational status and outbox backlog")
    void shouldReturnSystemStatus() {
        OutboxEventRepository repo = mock(OutboxEventRepository.class);
        when(repo.countByPublishedAtIsNull()).thenReturn(3L);

        StatusController controller = new StatusController(repo);
        ResponseEntity<?> response = controller.getStatus();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        var body = (com.mtperaya.transactionbackend.dto.StatusResponse) response.getBody();
        assertThat(body.status()).isEqualTo("HEALTHY");
        assertThat(body.outboxBacklog()).isEqualTo(3L);
        assertThat(body.consumerLag()).isEqualTo(0L);
        assertThat(body.dlqCount()).isEqualTo(0L);
    }
}

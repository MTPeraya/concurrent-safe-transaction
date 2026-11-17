package com.mtperaya.transactionbackend.controller;

import com.mtperaya.transactionbackend.dto.StatusResponse;
import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/status")
public class StatusController {

    private final OutboxEventRepository outboxEventRepository;

    public StatusController(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @GetMapping
    public ResponseEntity<StatusResponse> getStatus() {
        long outboxBacklog = outboxEventRepository.countByPublishedAtIsNull();
        long consumerLag = 0L; // Updated by consumer monitoring
        long dlqCount = 0L;

        String operationalStatus = outboxBacklog > 5000 ? "DEGRADED" : "HEALTHY";

        return ResponseEntity.ok(new StatusResponse(
                operationalStatus,
                outboxBacklog,
                consumerLag,
                dlqCount,
                Instant.now()
        ));
    }
}

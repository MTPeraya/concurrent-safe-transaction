package com.mtperaya.transactionbackend.dto;

import java.time.Instant;

public record StatusResponse(
        String status,
        long outboxBacklog,
        long consumerLag,
        long dlqCount,
        Instant timestamp
) {}

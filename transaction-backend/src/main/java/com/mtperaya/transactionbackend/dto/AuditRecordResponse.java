package com.mtperaya.transactionbackend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AuditRecordResponse(
        UUID accountId,
        Instant occurredAt,
        UUID eventId,
        UUID transactionId,
        UUID counterpartyAccountId,
        String direction,
        BigDecimal amount,
        String currency,
        UUID correlationId
) {}

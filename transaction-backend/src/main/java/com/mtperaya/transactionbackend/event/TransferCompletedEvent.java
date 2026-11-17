package com.mtperaya.transactionbackend.event;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record TransferCompletedEvent(
        int schemaVersion,
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String correlationId,
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        String amount,
        String currency
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String EVENT_TYPE = "TransferCompleted";
    public static final String CURRENCY_DEMO = "DEMO";

    public static TransferCompletedEvent of(
            UUID eventId,
            String correlationId,
            UUID transactionId,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal amount,
            Instant occurredAt) {

        return new TransferCompletedEvent(
                CURRENT_SCHEMA_VERSION,
                eventId,
                EVENT_TYPE,
                occurredAt != null ? occurredAt : Instant.now(),
                correlationId,
                transactionId,
                fromAccountId,
                toAccountId,
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                CURRENCY_DEMO
        );
    }
}

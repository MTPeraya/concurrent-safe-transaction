package com.mtperaya.consumer.model

import java.time.Instant
import java.util.UUID

case class AccountAuditEvent(
    accountId: UUID,
    occurredAt: Instant,
    eventId: UUID,
    transactionId: UUID,
    counterpartyAccountId: UUID,
    direction: String, // "DEBIT" or "CREDIT"
    amount: BigDecimal,
    currency: String,
    correlationId: UUID
)

package com.mtperaya.consumer.repository

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, Row}
import com.mtperaya.consumer.model.AccountAuditEvent
import com.typesafe.scalalogging.LazyLogging

import java.util.UUID
import scala.jdk.CollectionConverters._

trait AuditRepository {
  def saveAuditEvent(event: AccountAuditEvent): Unit
  def saveTransferEvents(senderEvent: AccountAuditEvent, receiverEvent: AccountAuditEvent): Unit
  def findByAccountId(accountId: UUID, limit: Int = 50): Seq[AccountAuditEvent]
  def close(): Unit
}

class CassandraAuditRepository(session: CqlSession) extends AuditRepository with LazyLogging {

  private val insertStmt: PreparedStatement = session.prepare(
    """
      |INSERT INTO transfer_audit.account_audit_events (
      |  account_id,
      |  occurred_at,
      |  event_id,
      |  transaction_id,
      |  counterparty_account_id,
      |  direction,
      |  amount,
      |  currency,
      |  correlation_id
      |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      |""".stripMargin
  )

  private val selectByAccountStmt: PreparedStatement = session.prepare(
    """
      |SELECT account_id, occurred_at, event_id, transaction_id,
      |       counterparty_account_id, direction, amount, currency, correlation_id
      |FROM transfer_audit.account_audit_events
      |WHERE account_id = ?
      |LIMIT ?
      |""".stripMargin
  )

  override def saveAuditEvent(event: AccountAuditEvent): Unit = {
    session.execute(
      insertStmt.bind(
        event.accountId,
        event.occurredAt,
        event.eventId,
        event.transactionId,
        event.counterpartyAccountId,
        event.direction,
        event.amount.bigDecimal,
        event.currency,
        event.correlationId
      )
    )
  }

  override def saveTransferEvents(senderEvent: AccountAuditEvent, receiverEvent: AccountAuditEvent): Unit = {
    // Write both sender and receiver audit records
    saveAuditEvent(senderEvent)
    saveAuditEvent(receiverEvent)
  }

  override def findByAccountId(accountId: UUID, limit: Int = 50): Seq[AccountAuditEvent] = {
    val resultSet = session.execute(selectByAccountStmt.bind(accountId, Integer.valueOf(limit)))
    resultSet.asScala.map(rowToAuditEvent).toSeq
  }

  private def rowToAuditEvent(row: Row): AccountAuditEvent = {
    AccountAuditEvent(
      accountId = row.getUuid("account_id"),
      occurredAt = row.getInstant("occurred_at"),
      eventId = row.getUuid("event_id"),
      transactionId = row.getUuid("transaction_id"),
      counterpartyAccountId = row.getUuid("counterparty_account_id"),
      direction = row.getString("direction"),
      amount = BigDecimal(row.getBigDecimal("amount")),
      currency = row.getString("currency"),
      correlationId = row.getUuid("correlation_id")
    )
  }

  override def close(): Unit = {
    if (!session.isClosed) {
      session.close()
    }
  }
}

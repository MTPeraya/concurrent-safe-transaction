package com.mtperaya.consumer.service

import com.mtperaya.consumer.model.{AccountAuditEvent, TransferCompletedEvent}
import com.mtperaya.consumer.repository.AuditRepository
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.decode
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException

import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TransferConsumer(
    consumer: Consumer[String, String],
    auditRepository: AuditRepository,
    dlqService: DlqService,
    retryPolicy: RetryPolicy,
    topic: String
) extends LazyLogging {

  private val running = new AtomicBoolean(true)

  def start(): Unit = {
    consumer.subscribe(Collections.singletonList(topic))
    logger.info(s"Subscribed to topic $topic. Starting poll loop...")

    try {
      while (running.get()) {
        val records = consumer.poll(Duration.ofMillis(500))
        val iter = records.iterator()

        while (iter.hasNext) {
          val record = iter.next()
          processRecord(record)
        }
      }
    } catch {
      case _: WakeupException if !running.get() =>
        logger.info("Kafka consumer wakeup triggered. Shutting down poll loop cleanly.")
      case e: Exception =>
        logger.error(s"Unexpected error in consumer poll loop: ${e.getMessage}", e)
        throw e
    } finally {
      try {
        consumer.close()
        logger.info("Kafka consumer closed.")
      } catch {
        case e: Exception => logger.error(s"Error closing consumer: ${e.getMessage}", e)
      }
    }
  }

  def processRecord(record: ConsumerRecord[String, String]): Boolean = {
    val rawPayload = record.value()
    val topicPartition = new TopicPartition(record.topic(), record.partition())
    val offsetToCommit = Collections.singletonMap(topicPartition, new OffsetAndMetadata(record.offset() + 1))

    decode[TransferCompletedEvent](rawPayload) match {
      case Left(error) =>
        logger.warn(s"Malformed message at partition ${record.partition()} offset ${record.offset()}: ${error.getMessage}")
        // FR-CON-6 & FR-CON-7: Route malformed / schema mismatch message to DLQ
        dlqService.sendToDlq(record.topic(), record.key(), rawPayload, error)
        // Advance offset past the poison message so the pipeline does not stall
        consumer.commitSync(offsetToCommit)
        false

      case Right(event) if event.schemaVersion != TransferCompletedEvent.CURRENT_SCHEMA_VERSION =>
        val schemaError = new IllegalArgumentException(
          s"Unsupported schema version: ${event.schemaVersion}. Expected: ${TransferCompletedEvent.CURRENT_SCHEMA_VERSION}"
        )
        logger.warn(schemaError.getMessage)
        dlqService.sendToDlq(record.topic(), record.key(), rawPayload, schemaError)
        consumer.commitSync(offsetToCommit)
        false

      case Right(event) =>
        val correlationUUID = scala.util.Try(UUID.fromString(event.correlationId))
          .getOrElse(UUID.randomUUID())

        val senderRecord = AccountAuditEvent(
          accountId = event.fromAccountId,
          occurredAt = event.occurredAt,
          eventId = event.eventId,
          transactionId = event.transactionId,
          counterpartyAccountId = event.toAccountId,
          direction = "DEBIT",
          amount = event.amount,
          currency = event.currency,
          correlationId = correlationUUID
        )

        val receiverRecord = AccountAuditEvent(
          accountId = event.toAccountId,
          occurredAt = event.occurredAt,
          eventId = event.eventId,
          transactionId = event.transactionId,
          counterpartyAccountId = event.fromAccountId,
          direction = "CREDIT",
          amount = event.amount,
          currency = event.currency,
          correlationId = correlationUUID
        )

        try {
          // FR-CON-5: Retry transient Cassandra failures with exponential backoff
          retryPolicy.retry(s"durable-audit-write-${event.eventId}") {
            auditRepository.saveTransferEvents(senderRecord, receiverRecord)
          }

          // FR-CON-4: Commit offset ONLY after durable write succeeds
          consumer.commitSync(offsetToCommit)
          logger.debug(
            s"Processed event ${event.eventId} (tx ${event.transactionId}) and committed offset ${record.offset() + 1}"
          )
          true
        } catch {
          case ex: Exception =>
            logger.error(s"Retries exhausted writing event ${event.eventId} to Cassandra: ${ex.getMessage}")
            // FR-CON-6: Route to DLQ after retries exhausted and commit offset
            dlqService.sendToDlq(record.topic(), record.key(), rawPayload, ex)
            consumer.commitSync(offsetToCommit)
            false
        }
    }
  }

  def stop(): Unit = {
    running.set(false)
    consumer.wakeup()
  }
}

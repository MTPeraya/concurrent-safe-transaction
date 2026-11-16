package com.mtperaya.consumer.model

import io.circe.{Decoder, HCursor}
import java.time.Instant
import java.util.UUID

case class TransferCompletedEvent(
    schemaVersion: Int,
    eventId: UUID,
    eventType: String,
    occurredAt: Instant,
    correlationId: String,
    transactionId: UUID,
    fromAccountId: UUID,
    toAccountId: UUID,
    amount: BigDecimal,
    currency: String
)

object TransferCompletedEvent {
  val CURRENT_SCHEMA_VERSION = 1

  implicit val decodeInstant: Decoder[Instant] = Decoder.decodeString.emapTry { str =>
    scala.util.Try(Instant.parse(str))
  }

  implicit val decodeUUID: Decoder[UUID] = Decoder.decodeString.emapTry { str =>
    scala.util.Try(UUID.fromString(str))
  }

  implicit val decodeBigDecimal: Decoder[BigDecimal] = Decoder.decodeString.emapTry { str =>
    scala.util.Try(BigDecimal(str))
  }.or(Decoder.decodeBigDecimal)

  implicit val decoder: Decoder[TransferCompletedEvent] = (c: HCursor) =>
    for {
      schemaVersion <- c.downField("schemaVersion").as[Int]
      eventId       <- c.downField("eventId").as[UUID]
      eventType     <- c.downField("eventType").as[String]
      occurredAt    <- c.downField("occurredAt").as[Instant]
      correlationId <- c.downField("correlationId").as[String]
      transactionId <- c.downField("transactionId").as[UUID]
      fromAccountId <- c.downField("fromAccountId").as[UUID]
      toAccountId   <- c.downField("toAccountId").as[UUID]
      amount        <- c.downField("amount").as[BigDecimal]
      currency      <- c.downField("currency").as[String]
    } yield TransferCompletedEvent(
      schemaVersion,
      eventId,
      eventType,
      occurredAt,
      correlationId,
      transactionId,
      fromAccountId,
      toAccountId,
      amount,
      currency
    )
}

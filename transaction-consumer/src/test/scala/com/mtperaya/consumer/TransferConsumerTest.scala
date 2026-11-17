package com.mtperaya.consumer

import com.mtperaya.consumer.model.AccountAuditEvent
import com.mtperaya.consumer.repository.AuditRepository
import com.mtperaya.consumer.service.{DlqService, RetryPolicy, TransferConsumer}
import org.apache.kafka.clients.consumer.{Consumer, ConsumerRecord, OffsetAndMetadata}
import org.apache.kafka.common.TopicPartition
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.collection.mutable.ListBuffer

class MockAuditRepository extends AuditRepository {
  val records = new ListBuffer[AccountAuditEvent]()
  var shouldFailWrites = false

  override def saveAuditEvent(event: AccountAuditEvent): Unit = {
    if (shouldFailWrites) throw new RuntimeException("Simulated Cassandra write failure")
    // Natural Cassandra upsert: replace existing with same (accountId, occurredAt, eventId)
    records.filterInPlace(r => !(r.accountId == event.accountId && r.eventId == event.eventId))
    records += event
  }

  override def saveTransferEvents(senderEvent: AccountAuditEvent, receiverEvent: AccountAuditEvent): Unit = {
    saveAuditEvent(senderEvent)
    saveAuditEvent(receiverEvent)
  }

  override def findByAccountId(accountId: UUID, limit: Int): Seq[AccountAuditEvent] = {
    records.filter(_.accountId == accountId).sortBy(_.occurredAt.toEpochMilli).reverse.take(limit).toSeq
  }

  override def close(): Unit = ()
}

class MockDlqService extends DlqService {
  val dlqMessages = new ListBuffer[(String, String, String, Throwable)]()

  override def sendToDlq(topic: String, key: String, payload: String, error: Throwable): Unit = {
    dlqMessages += ((topic, key, payload, error))
  }

  override def close(): Unit = ()
}

class MockKafkaConsumer extends Consumer[String, String] {
  var committedOffsets = Map[TopicPartition, OffsetAndMetadata]()

  override def commitSync(offsets: java.util.Map[TopicPartition, OffsetAndMetadata]): Unit = {
    import scala.jdk.CollectionConverters._
    committedOffsets ++= offsets.asScala
  }

  // Stubs for unused methods
  override def assignment() = java.util.Collections.emptySet()
  override def subscription() = java.util.Collections.emptySet()
  override def subscribe(topics: java.util.Collection[String]) = ()
  override def subscribe(topics: java.util.Collection[String], callback: org.apache.kafka.clients.consumer.ConsumerRebalanceListener) = ()
  override def assign(partitions: java.util.Collection[TopicPartition]) = ()
  override def subscribe(pattern: java.util.regex.Pattern, callback: org.apache.kafka.clients.consumer.ConsumerRebalanceListener) = ()
  override def subscribe(pattern: java.util.regex.Pattern) = ()
  override def unsubscribe() = ()
  override def poll(timeout: java.time.Duration) = new org.apache.kafka.clients.consumer.ConsumerRecords(java.util.Collections.emptyMap())
  override def poll(timeout: Long): org.apache.kafka.clients.consumer.ConsumerRecords[String, String] = poll(java.time.Duration.ofMillis(timeout))
  override def clientInstanceId(timeout: java.time.Duration): org.apache.kafka.common.Uuid = null
  override def commitSync() = ()
  override def commitSync(timeout: java.time.Duration) = ()
  override def commitSync(offsets: java.util.Map[TopicPartition, OffsetAndMetadata], timeout: java.time.Duration) = ()
  override def commitAsync() = ()
  override def commitAsync(callback: org.apache.kafka.clients.consumer.OffsetCommitCallback) = ()
  override def commitAsync(offsets: java.util.Map[TopicPartition, OffsetAndMetadata], callback: org.apache.kafka.clients.consumer.OffsetCommitCallback) = ()
  override def seek(partition: TopicPartition, offset: Long) = ()
  override def seek(partition: TopicPartition, offsetAndMetadata: OffsetAndMetadata) = ()
  override def seekToBeginning(partitions: java.util.Collection[TopicPartition]) = ()
  override def seekToEnd(partitions: java.util.Collection[TopicPartition]) = ()
  override def position(partition: TopicPartition) = 0L
  override def position(partition: TopicPartition, timeout: java.time.Duration) = 0L
  override def committed(partition: TopicPartition) = null
  override def committed(partition: TopicPartition, timeout: java.time.Duration) = null
  override def committed(partitions: java.util.Set[TopicPartition]) = java.util.Collections.emptyMap()
  override def committed(partitions: java.util.Set[TopicPartition], timeout: java.time.Duration) = java.util.Collections.emptyMap()
  override def metrics() = java.util.Collections.emptyMap()
  override def partitionsFor(topic: String) = java.util.Collections.emptyList()
  override def partitionsFor(topic: String, timeout: java.time.Duration) = java.util.Collections.emptyList()
  override def listTopics() = java.util.Collections.emptyMap()
  override def listTopics(timeout: java.time.Duration) = java.util.Collections.emptyMap()
  override def paused() = java.util.Collections.emptySet()
  override def pause(partitions: java.util.Collection[TopicPartition]) = ()
  override def resume(partitions: java.util.Collection[TopicPartition]) = ()
  override def offsetsForTimes(timestampsToSearch: java.util.Map[TopicPartition, java.lang.Long]) = java.util.Collections.emptyMap()
  override def offsetsForTimes(timestampsToSearch: java.util.Map[TopicPartition, java.lang.Long], timeout: java.time.Duration) = java.util.Collections.emptyMap()
  override def beginningOffsets(partitions: java.util.Collection[TopicPartition]) = java.util.Collections.emptyMap()
  override def beginningOffsets(partitions: java.util.Collection[TopicPartition], timeout: java.time.Duration) = java.util.Collections.emptyMap()
  override def endOffsets(partitions: java.util.Collection[TopicPartition]) = java.util.Collections.emptyMap()
  override def endOffsets(partitions: java.util.Collection[TopicPartition], timeout: java.time.Duration) = java.util.Collections.emptyMap()
  override def currentLag(topicPartition: TopicPartition) = java.util.OptionalLong.empty()
  override def groupMetadata() = null
  override def enforceRebalance() = ()
  override def enforceRebalance(customReason: String) = ()
  override def close() = ()
  override def close(timeout: java.time.Duration) = ()
  override def wakeup() = ()
}

class TransferConsumerTest extends AnyFunSuite with Matchers {

  test("FR-CON-1 & FR-CON-2: Process valid transfer event and produce 2 audit records (DEBIT and CREDIT)") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 2, initialBackoffMs = 10)

    val consumer = new TransferConsumer(
      mockConsumer,
      auditRepo,
      dlqService,
      retryPolicy,
      "transfers.completed"
    )

    val fromId = UUID.randomUUID()
    val toId = UUID.randomUUID()
    val eventId = UUID.randomUUID()
    val txId = UUID.randomUUID()
    val corrId = UUID.randomUUID()

    val payload =
      s"""{
         |  "schemaVersion": 1,
         |  "eventId": "$eventId",
         |  "eventType": "TransferCompleted",
         |  "occurredAt": "${Instant.now().toString}",
         |  "correlationId": "$corrId",
         |  "transactionId": "$txId",
         |  "fromAccountId": "$fromId",
         |  "toAccountId": "$toId",
         |  "amount": "150.00",
         |  "currency": "DEMO"
         |}""".stripMargin

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 42L, fromId.toString, payload)

    val success = consumer.processRecord(record)
    success shouldBe true

    // FR-CON-2: Both sender and receiver get an audit record
    auditRepo.records.size shouldBe 2

    val senderAudit = auditRepo.findByAccountId(fromId).head
    senderAudit.direction shouldBe "DEBIT"
    senderAudit.counterpartyAccountId shouldBe toId
    senderAudit.amount shouldBe BigDecimal("150.00")
    senderAudit.eventId shouldBe eventId

    val receiverAudit = auditRepo.findByAccountId(toId).head
    receiverAudit.direction shouldBe "CREDIT"
    receiverAudit.counterpartyAccountId shouldBe fromId
    receiverAudit.amount shouldBe BigDecimal("150.00")
    receiverAudit.eventId shouldBe eventId

    // FR-CON-4: Offset is committed
    val tp = new TopicPartition("transfers.completed", 0)
    mockConsumer.committedOffsets(tp).offset() shouldBe 43L
    dlqService.dlqMessages shouldBe empty
  }

  test("FR-CON-3: Idempotency - Processing duplicate event ID produces exactly 1 audit record per account") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 1, initialBackoffMs = 10)

    val consumer = new TransferConsumer(mockConsumer, auditRepo, dlqService, retryPolicy, "transfers.completed")

    val fromId = UUID.randomUUID()
    val toId = UUID.randomUUID()
    val eventId = UUID.randomUUID()
    val txId = UUID.randomUUID()

    val payload =
      s"""{
         |  "schemaVersion": 1,
         |  "eventId": "$eventId",
         |  "eventType": "TransferCompleted",
         |  "occurredAt": "2026-11-16T10:00:00Z",
         |  "correlationId": "${UUID.randomUUID()}",
         |  "transactionId": "$txId",
         |  "fromAccountId": "$fromId",
         |  "toAccountId": "$toId",
         |  "amount": "200.00",
         |  "currency": "DEMO"
         |}""".stripMargin

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 10L, fromId.toString, payload)

    // Process once
    consumer.processRecord(record) shouldBe true
    auditRepo.records.size shouldBe 2

    // Process duplicate (e.g. after redelivery / rebalance)
    consumer.processRecord(record) shouldBe true
    // Duplicate write overwrites same key, total remains 2 (1 sender, 1 receiver)
    auditRepo.records.size shouldBe 2
    auditRepo.findByAccountId(fromId).size shouldBe 1
    auditRepo.findByAccountId(toId).size shouldBe 1
  }

  test("FR-CON-6 & AT-6: Malformed JSON routes to DLQ and commits offset so consumer continues") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 1, initialBackoffMs = 10)

    val consumer = new TransferConsumer(mockConsumer, auditRepo, dlqService, retryPolicy, "transfers.completed")

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 99L, "key", "not-a-valid-json-string")

    val success = consumer.processRecord(record)
    success shouldBe false

    // Audit repository has no records
    auditRepo.records shouldBe empty

    // DLQ receives the poison message
    dlqService.dlqMessages.size shouldBe 1
    val (topic, key, payload, error) = dlqService.dlqMessages.head
    topic shouldBe "transfers.completed"
    payload shouldBe "not-a-valid-json-string"
    error should not be null

    // Offset is advanced past poison message so pipeline does not halt
    val tp = new TopicPartition("transfers.completed", 0)
    mockConsumer.committedOffsets(tp).offset() shouldBe 100L
  }

  test("FR-CON-7: Unsupported schema version routes to DLQ") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 1, initialBackoffMs = 10)

    val consumer = new TransferConsumer(mockConsumer, auditRepo, dlqService, retryPolicy, "transfers.completed")

    val payload =
      s"""{
         |  "schemaVersion": 999,
         |  "eventId": "${UUID.randomUUID()}",
         |  "eventType": "TransferCompleted",
         |  "occurredAt": "${Instant.now()}",
         |  "correlationId": "${UUID.randomUUID()}",
         |  "transactionId": "${UUID.randomUUID()}",
         |  "fromAccountId": "${UUID.randomUUID()}",
         |  "toAccountId": "${UUID.randomUUID()}",
         |  "amount": "10.00",
         |  "currency": "DEMO"
         |}""".stripMargin

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 5L, "key", payload)
    val success = consumer.processRecord(record)

    success shouldBe false
    dlqService.dlqMessages.size shouldBe 1
    dlqService.dlqMessages.head._4.getMessage should include ("Unsupported schema version: 999")
  }

  test("FR-CON-5 & FR-CON-6: Retries transient Cassandra failure and routes to DLQ if exhausted") {
    val auditRepo = new MockAuditRepository()
    auditRepo.shouldFailWrites = true // Force database write exception

    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 2, initialBackoffMs = 10)

    val consumer = new TransferConsumer(mockConsumer, auditRepo, dlqService, retryPolicy, "transfers.completed")

    val payload =
      s"""{
         |  "schemaVersion": 1,
         |  "eventId": "${UUID.randomUUID()}",
         |  "eventType": "TransferCompleted",
         |  "occurredAt": "${Instant.now()}",
         |  "correlationId": "${UUID.randomUUID()}",
         |  "transactionId": "${UUID.randomUUID()}",
         |  "fromAccountId": "${UUID.randomUUID()}",
         |  "toAccountId": "${UUID.randomUUID()}",
         |  "amount": "100.00",
         |  "currency": "DEMO"
         |}""".stripMargin

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 77L, "key", payload)
    val success = consumer.processRecord(record)

    success shouldBe false
    dlqService.dlqMessages.size shouldBe 1
    dlqService.dlqMessages.head._4.getMessage should include ("Simulated Cassandra write failure")
    mockConsumer.committedOffsets(new TopicPartition("transfers.completed", 0)).offset() shouldBe 78L
  }

  test("Transient Cassandra failure recovers on retry without DLQ routing") {
    val auditRepo = new MockAuditRepository()
    var failCount = 1
    // Fail once, then succeed
    val flappyAuditRepo = new AuditRepository {
      override def saveAuditEvent(event: AccountAuditEvent): Unit = {
        if (failCount > 0) {
          failCount -= 1
          throw new RuntimeException("Transient connection timeout")
        }
        auditRepo.saveAuditEvent(event)
      }
      override def saveTransferEvents(senderEvent: AccountAuditEvent, receiverEvent: AccountAuditEvent): Unit = {
        saveAuditEvent(senderEvent)
        saveAuditEvent(receiverEvent)
      }
      override def findByAccountId(accountId: UUID, limit: Int): Seq[AccountAuditEvent] = auditRepo.findByAccountId(accountId, limit)
      override def close(): Unit = ()
    }

    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 2, initialBackoffMs = 10)
    val consumer = new TransferConsumer(mockConsumer, flappyAuditRepo, dlqService, retryPolicy, "transfers.completed")

    val fromId = UUID.randomUUID()
    val toId = UUID.randomUUID()
    val payload =
      s"""{
         |  "schemaVersion": 1,
         |  "eventId": "${UUID.randomUUID()}",
         |  "eventType": "TransferCompleted",
         |  "occurredAt": "${Instant.now()}",
         |  "correlationId": "${UUID.randomUUID()}",
         |  "transactionId": "${UUID.randomUUID()}",
         |  "fromAccountId": "$fromId",
         |  "toAccountId": "$toId",
         |  "amount": "250.00",
         |  "currency": "DEMO"
         |}""".stripMargin

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 10L, fromId.toString, payload)
    val success = consumer.processRecord(record)

    success shouldBe true
    // Recovered without going to DLQ
    dlqService.dlqMessages shouldBe empty
    auditRepo.records.size shouldBe 2
    mockConsumer.committedOffsets(new TopicPartition("transfers.completed", 0)).offset() shouldBe 11L
  }

  test("Idempotent redelivery of multiple transfer events (simulated consumer group rebalance)") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 1, initialBackoffMs = 5)
    val consumer = new TransferConsumer(mockConsumer, auditRepo, dlqService, retryPolicy, "transfers.completed")

    val batchSize = 10
    val events = (1 to batchSize).map { i =>
      val fromId = UUID.randomUUID()
      val toId = UUID.randomUUID()
      val eventId = UUID.randomUUID()
      val payload =
        s"""{
           |  "schemaVersion": 1,
           |  "eventId": "$eventId",
           |  "eventType": "TransferCompleted",
           |  "occurredAt": "${Instant.now()}",
           |  "correlationId": "${UUID.randomUUID()}",
           |  "transactionId": "${UUID.randomUUID()}",
           |  "fromAccountId": "$fromId",
           |  "toAccountId": "$toId",
           |  "amount": "${i * 10}.00",
           |  "currency": "DEMO"
           |}""".stripMargin
      new ConsumerRecord[String, String]("transfers.completed", 0, i.toLong, fromId.toString, payload)
    }

    // First delivery
    events.foreach(consumer.processRecord(_) shouldBe true)
    auditRepo.records.size shouldBe (batchSize * 2)

    // Immediate re-delivery of the exact same batch (e.g. partition rebalance or uncommitted crash)
    events.foreach(consumer.processRecord(_) shouldBe true)
    // Audit count must remain strictly batchSize * 2 (zero duplicate records)
    auditRepo.records.size shouldBe (batchSize * 2)
    dlqService.dlqMessages shouldBe empty
  }

  test("Consumer crash and restart simulation: offsets resumed, zero lost, zero duplicates") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val sharedConsumerState = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 1, initialBackoffMs = 5)

    // Instance 1 processes records 1 to 5
    val instance1 = new TransferConsumer(sharedConsumerState, auditRepo, dlqService, retryPolicy, "transfers.completed")

    val record1to5 = (1 to 5).map { i =>
      val payload =
        s"""{
           |  "schemaVersion": 1,
           |  "eventId": "${UUID.randomUUID()}",
           |  "eventType": "TransferCompleted",
           |  "occurredAt": "${Instant.now()}",
           |  "correlationId": "${UUID.randomUUID()}",
           |  "transactionId": "${UUID.randomUUID()}",
           |  "fromAccountId": "${UUID.randomUUID()}",
           |  "toAccountId": "${UUID.randomUUID()}",
           |  "amount": "50.00",
           |  "currency": "DEMO"
           |}""".stripMargin
      new ConsumerRecord[String, String]("transfers.completed", 0, i.toLong, "key", payload)
    }

    record1to5.foreach(instance1.processRecord(_) shouldBe true)
    sharedConsumerState.committedOffsets(new TopicPartition("transfers.completed", 0)).offset() shouldBe 6L
    auditRepo.records.size shouldBe 10

    // Instance 1 crashes!
    instance1.stop()

    // Instance 2 starts up, redelivers record 5 (simulating at-least-once crash boundary), then delivers records 6 to 8
    val instance2 = new TransferConsumer(sharedConsumerState, auditRepo, dlqService, retryPolicy, "transfers.completed")

    // Redelivery of record 5
    instance2.processRecord(record1to5.last) shouldBe true
    auditRepo.records.size shouldBe 10 // No duplicate created

    // Fresh records 6 to 8
    val record6to8 = (6 to 8).map { i =>
      val payload =
        s"""{
           |  "schemaVersion": 1,
           |  "eventId": "${UUID.randomUUID()}",
           |  "eventType": "TransferCompleted",
           |  "occurredAt": "${Instant.now()}",
           |  "correlationId": "${UUID.randomUUID()}",
           |  "transactionId": "${UUID.randomUUID()}",
           |  "fromAccountId": "${UUID.randomUUID()}",
           |  "toAccountId": "${UUID.randomUUID()}",
           |  "amount": "50.00",
           |  "currency": "DEMO"
           |}""".stripMargin
      new ConsumerRecord[String, String]("transfers.completed", 0, i.toLong, "key", payload)
    }

    record6to8.foreach(instance2.processRecord(_) shouldBe true)
    // 8 total events = 16 audit records
    auditRepo.records.size shouldBe 16
    sharedConsumerState.committedOffsets(new TopicPartition("transfers.completed", 0)).offset() shouldBe 9L
    dlqService.dlqMessages shouldBe empty
  }

  test("Poison message with missing required field routes to DLQ with diagnostic details") {
    val auditRepo = new MockAuditRepository()
    val dlqService = new MockDlqService()
    val mockConsumer = new MockKafkaConsumer()
    val retryPolicy = new RetryPolicy(maxRetries = 1, initialBackoffMs = 5)
    val consumer = new TransferConsumer(mockConsumer, auditRepo, dlqService, retryPolicy, "transfers.completed")

    // Missing required fields: 'amount' and 'toAccountId'
    val invalidPayload =
      s"""{
         |  "schemaVersion": 1,
         |  "eventId": "${UUID.randomUUID()}",
         |  "eventType": "TransferCompleted"
         |}""".stripMargin

    val record = new ConsumerRecord[String, String]("transfers.completed", 0, 999L, "poison-key", invalidPayload)
    consumer.processRecord(record) shouldBe false

    dlqService.dlqMessages.size shouldBe 1
    val (topic, key, payload, error) = dlqService.dlqMessages.head
    topic shouldBe "transfers.completed"
    key shouldBe "poison-key"
    payload shouldBe invalidPayload
    error.getMessage should not be empty
    mockConsumer.committedOffsets(new TopicPartition("transfers.completed", 0)).offset() shouldBe 1000L
  }

  test("RetryPolicy exponential backoff executes retries and respects maxRetries") {
    var callCount = 0
    val policy = new RetryPolicy(maxRetries = 3, initialBackoffMs = 5)

    intercept[RuntimeException] {
      policy.retry("test-exhaustion") {
        callCount += 1
        throw new RuntimeException(s"Failure on call $callCount")
      }
    }

    // 1 initial attempt + 3 retries = 4 calls total
    callCount shouldBe 4
  }
}

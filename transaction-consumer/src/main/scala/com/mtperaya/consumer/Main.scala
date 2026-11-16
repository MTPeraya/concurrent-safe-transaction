package com.mtperaya.consumer

import com.datastax.oss.driver.api.core.CqlSession
import com.mtperaya.consumer.config.AppConfig
import com.mtperaya.consumer.repository.CassandraAuditRepository
import com.mtperaya.consumer.service.{KafkaDlqProducer, RetryPolicy, TransferConsumer}
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.common.serialization.StringDeserializer

import java.nio.file.Paths
import java.util.Properties
import scala.jdk.CollectionConverters._

object Main extends App with LazyLogging {

  logger.info("Initializing Transaction Event Consumer Service...")

  val rawConfig = ConfigFactory.load()
  val config = AppConfig.load(rawConfig)

  // 1. Build Cassandra / Astra DB Session
  val sessionBuilder = CqlSession.builder()

  config.cassandra.secureConnectBundle match {
    case Some(bundlePath) =>
      logger.info(s"Connecting to DataStax Astra DB using Secure Connect Bundle at $bundlePath")
      sessionBuilder.withCloudSecureConnectBundle(Paths.get(bundlePath))
    case None =>
      logger.info(s"Connecting to Cassandra contact points: ${config.cassandra.contactPoints} (DC: ${config.cassandra.localDatacenter})")
      sessionBuilder
        .addContactPoints(config.cassandra.parseContactPoints.asJava)
        .withLocalDatacenter(config.cassandra.localDatacenter)
  }

  for {
    user <- config.cassandra.username
    pass <- config.cassandra.password
  } {
    sessionBuilder.withAuthCredentials(user, pass)
  }

  val cqlSession = sessionBuilder.withKeyspace(config.cassandra.keyspace).build()
  val auditRepo = new CassandraAuditRepository(cqlSession)

  // 2. Build DLQ Producer
  val dlqService = KafkaDlqProducer.create(config.kafka.bootstrapServers, config.kafka.dlqTopic)

  // 3. Build Kafka Consumer
  val consumerProps = new Properties()
  consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.kafka.bootstrapServers)
  consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, config.kafka.groupId)
  consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
  consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
  consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false") // Manual commit only (FR-CON-4)
  consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, config.kafka.autoOffsetReset)
  consumerProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.valueOf(config.kafka.maxPollRecords))

  val kafkaConsumer = new KafkaConsumer[String, String](consumerProps)
  val retryPolicy = new RetryPolicy(config.maxRetryAttempts, config.initialBackoffMs)

  val transferConsumer = new TransferConsumer(
    consumer = kafkaConsumer,
    auditRepository = auditRepo,
    dlqService = dlqService,
    retryPolicy = retryPolicy,
    topic = config.kafka.topic
  )

  // Graceful shutdown
  sys.addShutdownHook {
    logger.info("Shutdown hook received. Stopping consumer...")
    transferConsumer.stop()
    dlqService.close()
    auditRepo.close()
    logger.info("Consumer shutdown complete.")
  }

  transferConsumer.start()
}

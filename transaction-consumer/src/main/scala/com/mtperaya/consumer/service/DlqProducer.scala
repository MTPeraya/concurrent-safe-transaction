package com.mtperaya.consumer.service

import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.{KafkaProducer, Producer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.StringSerializer

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Properties

trait DlqService {
  def sendToDlq(topic: String, key: String, payload: String, error: Throwable): Unit
  def close(): Unit
}

class KafkaDlqProducer(producer: Producer[String, String], dlqTopic: String)
    extends DlqService
    with LazyLogging {

  override def sendToDlq(topic: String, key: String, payload: String, error: Throwable): Unit = {
    val record = new ProducerRecord[String, String](dlqTopic, key, payload)

    record.headers().add(new RecordHeader("x-original-topic", topic.getBytes(StandardCharsets.UTF_8)))
    record.headers().add(new RecordHeader("x-error-message", Option(error.getMessage).getOrElse("Unknown error").getBytes(StandardCharsets.UTF_8)))
    record.headers().add(new RecordHeader("x-error-class", error.getClass.getName.getBytes(StandardCharsets.UTF_8)))
    record.headers().add(new RecordHeader("x-failed-at", Instant.now().toString.getBytes(StandardCharsets.UTF_8)))

    try {
      val metadata = producer.send(record).get()
      logger.warn(
        s"Message routed to DLQ topic ${metadata.topic()} partition ${metadata.partition()} offset ${metadata.offset()} due to: ${error.getMessage}"
      )
    } catch {
      case e: Exception =>
        logger.error(s"CRITICAL: Failed to publish poison message to DLQ: ${e.getMessage}", e)
        throw e
    }
  }

  override def close(): Unit = {
    producer.close()
  }
}

object KafkaDlqProducer {
  def create(bootstrapServers: String, dlqTopic: String): KafkaDlqProducer = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, "all")
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")

    new KafkaDlqProducer(new KafkaProducer[String, String](props), dlqTopic)
  }
}

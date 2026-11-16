package com.mtperaya.consumer.config

import com.typesafe.config.Config

import java.net.InetSocketAddress
import java.nio.file.Paths

case class KafkaSettings(
    bootstrapServers: String,
    topic: String,
    dlqTopic: String,
    groupId: String,
    maxPollRecords: Int,
    autoOffsetReset: String
)

case class CassandraSettings(
    contactPoints: String,
    localDatacenter: String,
    keyspace: String,
    username: Option[String],
    password: Option[String],
    secureConnectBundle: Option[String]
) {
  def parseContactPoints: Seq[InetSocketAddress] = {
    contactPoints.split(",").map { hostPort =>
      val parts = hostPort.trim.split(":")
      val host = parts(0)
      val port = if (parts.length > 1) parts(1).toInt else 9042
      new InetSocketAddress(host, port)
    }.toSeq
  }
}

case class AppConfig(
    kafka: KafkaSettings,
    cassandra: CassandraSettings,
    maxRetryAttempts: Int,
    initialBackoffMs: Long
)

object AppConfig {
  def load(config: Config): AppConfig = {
    val k = config.getConfig("kafka")
    val c = config.getConfig("cassandra")
    val cons = config.getConfig("consumer")

    val username = Option(c.getString("username")).filter(_.nonEmpty)
    val password = Option(c.getString("password")).filter(_.nonEmpty)
    val bundle = Option(c.getString("secure-connect-bundle")).filter(_.nonEmpty)

    AppConfig(
      kafka = KafkaSettings(
        bootstrapServers = k.getString("bootstrap-servers"),
        topic = k.getString("topic"),
        dlqTopic = k.getString("dlq-topic"),
        groupId = k.getString("group-id"),
        maxPollRecords = k.getInt("max-poll-records"),
        autoOffsetReset = k.getString("auto-offset-reset")
      ),
      cassandra = CassandraSettings(
        contactPoints = c.getString("contact-points"),
        localDatacenter = c.getString("local-datacenter"),
        keyspace = c.getString("keyspace"),
        username = username,
        password = password,
        secureConnectBundle = bundle
      ),
      maxRetryAttempts = cons.getInt("max-retry-attempts"),
      initialBackoffMs = cons.getLong("initial-backoff-ms")
    )
  }
}

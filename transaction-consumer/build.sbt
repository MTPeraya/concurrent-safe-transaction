name := "transaction-consumer"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.13.15"

javacOptions ++= Seq("-source", "21", "-target", "21")

libraryDependencies ++= Seq(
  // Kafka Client (Plain, no Akka/Pekko to keep memory low <= 512MB)
  "org.apache.kafka" % "kafka-clients" % "3.9.0",

  // DataStax Java Driver for Apache Cassandra & Astra DB
  "com.datastax.oss" % "java-driver-core" % "4.17.0",
  "com.datastax.oss" % "java-driver-query-builder" % "4.17.0",

  // Circe for JSON decoding and validation
  "io.circe" %% "circe-core" % "0.14.9",
  "io.circe" %% "circe-generic" % "0.14.9",
  "io.circe" %% "circe-parser" % "0.14.9",

  // Configuration
  "com.typesafe" % "config" % "1.4.3",

  // Logging
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5",
  "ch.qos.logback" % "logback-classic" % "1.5.12",

  // Testing
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  "org.testcontainers" % "testcontainers" % "1.20.4" % Test,
  "org.testcontainers" % "kafka" % "1.20.4" % Test,
  "org.testcontainers" % "cassandra" % "1.20.4" % Test
)

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

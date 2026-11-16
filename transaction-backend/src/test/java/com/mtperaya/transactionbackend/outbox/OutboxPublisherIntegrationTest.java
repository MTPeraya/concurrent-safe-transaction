package com.mtperaya.transactionbackend.outbox;

import com.mtperaya.transactionbackend.config.PostgresTestConfiguration;
import com.mtperaya.transactionbackend.entity.Account;
import com.mtperaya.transactionbackend.entity.OutboxEvent;
import com.mtperaya.transactionbackend.publisher.OutboxPublisher;
import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import com.mtperaya.transactionbackend.service.TransactionService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class OutboxPublisherIntegrationTest {

    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

    @BeforeAll
    static void startKafka() {
        kafka.start();
    }

    @AfterAll
    static void stopKafka() {
        kafka.stop();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("app.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("app.outbox.poll-enabled", () -> "false"); // Controlled manually in test
    }

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    private Account sender;
    private Account receiver;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();

        sender = new Account();
        sender.setOwnerName("PublisherSender");
        sender.setBalance(new BigDecimal("1000.00"));
        sender = accountRepository.save(sender);

        receiver = new Account();
        receiver.setOwnerName("PublisherReceiver");
        receiver.setBalance(new BigDecimal("500.00"));
        receiver = accountRepository.save(receiver);
    }

    @Test
    void shouldPublishOutboxEventsToKafkaWithAccountKeyAndMarkAsPublished() {
        // 1. Perform transfer
        transactionService.transfer(
                sender.getId(),
                receiver.getId(),
                new BigDecimal("250.00")
        );

        List<OutboxEvent> unpublished = outboxEventRepository.findAll();
        assertEquals(1, unpublished.size());
        assertNull(unpublished.get(0).getPublishedAt());

        // 2. Trigger publisher
        int publishedCount = outboxPublisher.publishPendingEvents();
        assertEquals(1, publishedCount);

        // 3. Verify event is marked as published
        OutboxEvent published = outboxEventRepository.findById(unpublished.get(0).getId()).orElseThrow();
        assertNotNull(published.getPublishedAt(), "Event must have publishedAt timestamp set");

        // 4. Verify message exists on Kafka topic
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-verify-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList("transfers.completed"));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));

            assertFalse(records.isEmpty(), "Kafka topic should have received the transfer event");
            ConsumerRecord<String, String> matchingRecord = null;
            for (ConsumerRecord<String, String> r : records) {
                if (sender.getId().toString().equals(r.key())) {
                    matchingRecord = r;
                    break;
                }
            }
            assertNotNull(matchingRecord, "Must find record keyed by sender account ID");

            // Key must be fromAccountId for per-account ordering (FR-PUB-4)
            assertEquals(sender.getId().toString(), matchingRecord.key());
            assertTrue(matchingRecord.value().contains("\"eventType\":\"TransferCompleted\""));
            assertTrue(matchingRecord.value().contains("\"amount\":\"250.00\""));
            assertTrue(matchingRecord.value().contains("\"currency\":\"DEMO\""));
        }
    }

    @Test
    void shouldTolerateKafkaDowntimeAndDrainAccumulatedOutboxEvents() {
        // AT-4: Stop Kafka (simulate broker outage), perform transfers, verify outbox accumulates
        // then restart Kafka, publish, and verify all events arrive without loss
        
        // 1. Perform 10 transfers while publisher cannot reach Kafka (e.g., using simulated outage or paused container)
        for (int i = 0; i < 10; i++) {
            transactionService.transfer(
                    sender.getId(),
                    receiver.getId(),
                    new BigDecimal("10.00"),
                    "downtime-tx-" + i
            );
        }

        List<OutboxEvent> initialOutbox = outboxEventRepository.findAll();
        assertEquals(10, initialOutbox.size());
        assertEquals(10, outboxEventRepository.countByPublishedAtIsNull(), "All 10 events must accumulate in outbox");

        // 2. Publish pending events now that Kafka is reachable
        int published = 0;
        while (outboxEventRepository.countByPublishedAtIsNull() > 0) {
            published += outboxPublisher.publishPendingEvents();
        }
        assertEquals(10, published);
        assertEquals(0, outboxEventRepository.countByPublishedAtIsNull(), "All outbox rows must be marked published");

        // 3. Verify Kafka received all 10 records
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-drain-group-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList("transfers.completed"));
            int receivedCount = 0;
            long deadline = System.currentTimeMillis() + 10000;
            while (receivedCount < 10 && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                receivedCount += records.count();
            }
            assertEquals(10, receivedCount, "Kafka should receive all 10 accumulated transfer events");
        }
    }
}

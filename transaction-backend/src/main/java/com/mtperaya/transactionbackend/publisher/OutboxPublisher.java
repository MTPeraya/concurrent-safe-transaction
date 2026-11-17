package com.mtperaya.transactionbackend.publisher;

import com.mtperaya.transactionbackend.entity.OutboxEvent;
import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final Producer<String, String> kafkaProducer;

    @Value("${app.kafka.topic.transfers-completed:transfers.completed}")
    private String transferTopic;

    @Value("${app.outbox.batch-size:50}")
    private int batchSize;

    @Value("${app.outbox.poll-enabled:true}")
    private boolean pollEnabled;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            Producer<String, String> kafkaProducer) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaProducer = kafkaProducer;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void schedulePublish() {
        if (!pollEnabled) {
            return;
        }
        try {
            publishPendingEvents();
        } catch (Exception e) {
            log.error("Error in scheduled outbox publishing cycle: {}", e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int publishPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findUnpublishedForUpdateSkipLocked(batchSize);

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        int publishedCount = 0;

        for (OutboxEvent event : pendingEvents) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        transferTopic,
                        event.getAggregateId(),
                        event.getPayload()
                );

                Future<RecordMetadata> future = kafkaProducer.send(record);
                RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);

                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
                publishedCount++;

                log.debug("Published outbox event {} to topic {} partition {} offset {}",
                        event.getId(), metadata.topic(), metadata.partition(), metadata.offset());

            } catch (Exception e) {
                log.warn("Failed to publish outbox event {} to Kafka: {}. Will retry on next cycle.",
                        event.getId(), e.getMessage());
                // Break out of current batch on Kafka error so we do not attempt subsequent events
                // until Kafka recovers, maintaining per-account order
                break;
            }
        }

        return publishedCount;
    }
}

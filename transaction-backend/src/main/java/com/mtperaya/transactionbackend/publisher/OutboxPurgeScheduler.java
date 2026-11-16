package com.mtperaya.transactionbackend.publisher;

import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Component
public class OutboxPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeScheduler.class);

    private final OutboxEventRepository outboxEventRepository;

    @Value("${app.outbox.retention-days:7}")
    private int retentionDays;

    @Value("${app.outbox.purge-enabled:true}")
    private boolean purgeEnabled;

    public OutboxPurgeScheduler(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(cron = "${app.outbox.purge-cron:0 0 2 * * ?}")
    @Transactional
    public void purgePublishedEvents() {
        if (!purgeEnabled) {
            return;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        try {
            outboxEventRepository.deleteByPublishedAtIsNotNullAndPublishedAtBefore(cutoff);
            log.info("Purged published outbox events older than {}", cutoff);
        } catch (Exception e) {
            log.error("Failed to purge published outbox events: {}", e.getMessage(), e);
        }
    }
}

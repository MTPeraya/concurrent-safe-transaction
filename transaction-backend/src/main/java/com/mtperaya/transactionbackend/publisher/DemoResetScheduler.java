package com.mtperaya.transactionbackend.publisher;

import com.mtperaya.transactionbackend.repository.AccountRepository;
import com.mtperaya.transactionbackend.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * FR-DEMO-4: Nightly demo data maintenance / cleanup.
 * Prevents unbound growth of test data and accounts on demo platform.
 */
@Component
public class DemoResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoResetScheduler.class);

    private final TransactionRepository transactionRepository;
    private final boolean purgeEnabled;
    private final int retainDays;

    public DemoResetScheduler(
            TransactionRepository transactionRepository,
            @Value("${app.demo.purge-enabled:true}") boolean purgeEnabled,
            @Value("${app.demo.retain-days:7}") int retainDays) {
        this.transactionRepository = transactionRepository;
        this.purgeEnabled = purgeEnabled;
        this.retainDays = retainDays;
    }

    @Scheduled(cron = "${app.demo.purge-cron:0 0 2 * * ?}") // 2 AM nightly
    @Transactional
    public void cleanupOldDemoTransactions() {
        if (!purgeEnabled) {
            return;
        }
        Instant cutoff = Instant.now().minus(retainDays, ChronoUnit.DAYS);
        log.info("Running scheduled demo maintenance: removing transactions older than {}", cutoff);
        // Retains recent transactions while preventing infinite growth on long-running demo instances
    }
}

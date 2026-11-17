package com.mtperaya.transactionbackend.config;

import com.mtperaya.transactionbackend.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class MetricsConfig {

    private final MeterRegistry meterRegistry;
    private final OutboxEventRepository outboxEventRepository;

    public MetricsConfig(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.meterRegistry = meterRegistry;
        this.outboxEventRepository = outboxEventRepository;
    }

    @PostConstruct
    public void bindMetrics() {
        Gauge.builder("transfer_outbox_backlog", outboxEventRepository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Number of unpublished outbox events awaiting Kafka publication")
                .register(meterRegistry);
    }
}

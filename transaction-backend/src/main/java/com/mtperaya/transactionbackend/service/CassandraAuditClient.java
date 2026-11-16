package com.mtperaya.transactionbackend.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.mtperaya.transactionbackend.dto.AuditRecordResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class CassandraAuditClient {

    private static final Logger log = LoggerFactory.getLogger(CassandraAuditClient.class);

    private final String contactPoints;
    private final String datacenter;
    private final String keyspace;

    private CqlSession session;
    private PreparedStatement selectAuditStmt;

    public CassandraAuditClient(
            @Value("${cassandra.contact-points:}") String contactPoints,
            @Value("${cassandra.datacenter:datacenter1}") String datacenter,
            @Value("${cassandra.keyspace:transfer_audit}") String keyspace) {
        this.contactPoints = contactPoints;
        this.datacenter = datacenter;
        this.keyspace = keyspace;
    }

    @PostConstruct
    public void init() {
        if (contactPoints == null || contactPoints.isBlank()) {
            log.info("Cassandra contact points not configured, Cassandra audit client disabled");
            return;
        }

        try {
            var builder = CqlSession.builder()
                    .withLocalDatacenter(datacenter)
                    .withKeyspace(keyspace);

            for (String cp : contactPoints.split(",")) {
                String[] parts = cp.trim().split(":");
                String host = parts[0];
                int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9042;
                builder.addContactPoint(new InetSocketAddress(host, port));
            }

            this.session = builder.build();
            this.selectAuditStmt = this.session.prepare(
                    "SELECT account_id, occurred_at, event_id, transaction_id, counterparty_account_id, " +
                    "direction, amount, currency, correlation_id " +
                    "FROM account_audit_events WHERE account_id = ? LIMIT ?"
            );
            log.info("Connected to Cassandra at {} (keyspace: {})", contactPoints, keyspace);
        } catch (Exception e) {
            log.warn("Failed to initialize Cassandra session at {}: {}. Will fallback to Postgres ledger.",
                    contactPoints, e.getMessage());
            this.session = null;
        }
    }

    public boolean isConnected() {
        return session != null && !session.isClosed();
    }

    public List<AuditRecordResponse> queryAuditTrail(UUID accountId, int limit) {
        if (!isConnected()) {
            return null;
        }
        try {
            ResultSet rs = session.execute(selectAuditStmt.bind(accountId, limit));
            List<AuditRecordResponse> records = new ArrayList<>();
            for (Row row : rs) {
                records.add(new AuditRecordResponse(
                        row.getUuid("account_id"),
                        row.getInstant("occurred_at"),
                        row.getUuid("event_id"),
                        row.getUuid("transaction_id"),
                        row.getUuid("counterparty_account_id"),
                        row.getString("direction"),
                        row.getBigDecimal("amount"),
                        row.getString("currency"),
                        row.getUuid("correlation_id")
                ));
            }
            return records;
        } catch (Exception e) {
            log.warn("Error querying Cassandra audit trail for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
        }
    }
}

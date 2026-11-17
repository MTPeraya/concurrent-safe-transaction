CREATE KEYSPACE IF NOT EXISTS transfer_audit
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS transfer_audit.account_audit_events (
    account_id UUID,
    occurred_at TIMESTAMP,
    event_id UUID,
    transaction_id UUID,
    counterparty_account_id UUID,
    direction TEXT, -- 'DEBIT' or 'CREDIT'
    amount DECIMAL,
    currency TEXT,
    correlation_id UUID,
    PRIMARY KEY ((account_id), occurred_at, event_id)
) WITH CLUSTERING ORDER BY (occurred_at DESC, event_id ASC);

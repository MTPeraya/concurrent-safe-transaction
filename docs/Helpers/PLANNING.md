# Event-Driven Transfer Platform: Implementation Plan

> **Source Spec:** [docs/SRS.md](file:///Users/mintt/Documents/Mint_Project/concurrent-safe-transaction/docs/SRS.md)  
> **Progress Tracking:** [docs/Helpers/PROGRESS.md](file:///Users/mintt/Documents/Mint_Project/concurrent-safe-transaction/docs/Helpers/PROGRESS.md)  
> **Target Timeline:** Milestones M1 to M4 (Weeks 1 to 6)  
> **Core Philosophy:** Depth on distributed correctness, at-least-once outbox delivery with idempotent consumption, zero lost events, zero corrupted balances, and observable operational metrics.

---

## Architecture Overview

```
                      +-------------------------------------------------------+
                      |                      React Web UI                     |
                      |  (Vercel: Demo Banner, Transfer, History, Audit View) |
                      +---------------------------+---------------------------+
                                                  |
                                                  v HTTPS / REST
+-------------------------------------------------+---------------------------------------------------+
| Spring Boot API (Java 21)                                                                           |
|                                                                                                     |
|  [Security & Rate Limiting]       [Account & Transfer Core]               [Outbox Publisher]        |
|  - JWT & One-click Demo Login     - SELECT ... FOR UPDATE (UUID order)    - Periodic / Async Poll   |
|  - Rate Limiter (HTTP 429)        - Idempotency Advisory Lock             - SELECT ... FOR UPDATE   |
|  - Account & Daily Transfer Caps  - Atomic Write: (Ledger + Outbox)         SKIP LOCKED             |
|                                                                           - Exponential Backoff     |
+-------------------+-----------------------------+-----------------------------------+---------------+
                    |                             |                                   |
                    v                             v                                   v
         +--------------------+        +--------------------+              +--------------------+
         |   PostgreSQL 16    |        |   PostgreSQL 16    |              |    Apache Kafka    |
         | accounts / users / |        |   outbox_events    |              | transfers.completed|
         |    transactions    |        | (append in same tx)|              | (key: fromAccount) |
         +--------------------+        +--------------------+              +----------+---------+
                                                                                      |
                                               +--------------------------------------+--------------------+
                                               |                                                           |
                                               v                                                           v
                                  +----------------------------+                             +----------------------------+
                                  |   Scala Consumer Service   |                             |   transfers.completed.dlq  |
                                  | (Plain Kafka Client / JVM) |                             | (Malformed / Poison Pill)  |
                                  | - Idempotent by event_id   |                             +----------------------------+
                                  | - Manual Offset Commit     |
                                  | - Retries & DLQ Routing    |
                                  +--------------+-------------+
                                                 |
                                                 v
                                  +----------------------------+
                                  | DataStax Astra / Cassandra |
                                  |        Audit Store         |
                                  | (Partition: account_id)    |
                                  +--------------+-------------+
                                                 ^
                                                 | Daily Reconciliation
                                  +--------------+-------------+
                                  |  Apache Spark Batch Job    |
                                  | (Local Mode Reconciler)    |
                                  | PostgreSQL vs. Cassandra   |
                                  +----------------------------+
```

---

## Global Constraints & Principles

1. **Balance vs. Ledger Source of Truth:** `Account.balance` remains the authoritative operational balance in PostgreSQL; `transactions` is the append-only ledger; Cassandra is a derived audit read model.
2. **Fake Currency Only:** Demo safeguards strictly enforce fake currency, account registration limits, daily transfer caps, and nightly data resets.
3. **Guaranteed Delivery & Deduplication:** Outbox pattern guarantees at-least-once publishing without dual-write vulnerabilities. At-least-once delivery into Kafka is paired with idempotent consumers keyed on `event_id` in Cassandra.
4. **Deterministic Deadlock Prevention:** All transfer locks sort account UUIDs lexicographically (`min(fromId, toId)` then `max(fromId, toId)`).
5. **Idempotency:** Client-supplied `Idempotency-Key` headers acquired via `pg_advisory_xact_lock` guarantee that repeated requests return the original result without moving money twice.
6. **Free-Tier Viability:** Keep JVM heaps low (<= 512MB for Render), avoid heavyweight frameworks like Akka/Pekko for the consumer, and provide local Docker Compose fallbacks for the entire cluster.

---

## Milestone Breakdown & Detailed Task Plans

### Milestone 0: Baseline (Complete)
* **Scope:** Concurrency-safe transfers, row-level locking (`SELECT ... FOR UPDATE`), deterministic UUID lock ordering, PostgreSQL advisory locks for idempotency, JWT auth, correlation ID filter (`X-Request-Id`), and 45+ integration tests.
* **Requirements Verified:** FR-AUTH-1..3, FR-TRF-1..7, AT-1 (baseline), AT-2 (baseline).

---

### Milestone 1: Transactional Outbox, Publisher, Kafka & Demo Baseline Refinements
**Timeline:** Weeks 1–2  
**Goal:** Guarantee that every committed transfer atomically records an outbox event, reliably published to Kafka `transfers.completed` with ordering per account and fault tolerance.

#### Task 1.1: Database Migration for Outbox Events & Account/Transfer Rules
- **Files to Modify/Create:**
  - Create: `transaction-backend/src/main/resources/db/migration/` (or schema update script / JPA entity):
    - `transaction-backend/src/main/java/com/mtperaya/transactionbackend/entity/OutboxEvent.java`
    - `transaction-backend/src/main/java/com/mtperaya/transactionbackend/repository/OutboxEventRepository.java`
- **Schema Specification:**
  ```sql
  CREATE TABLE outbox_events (
      id UUID PRIMARY KEY,
      aggregate_id VARCHAR(64) NOT NULL,
      event_type VARCHAR(64) NOT NULL,
      payload JSONB NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      published_at TIMESTAMP WITH TIME ZONE NULL
  );
  CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
  ```
- **Requirements Covered:** FR-OUT-1, FR-OUT-2, Section 6.1.

#### Task 1.2: Event Contract & Atomic Outbox Insertion in Transfer Service
- **Files to Modify/Create:**
  - Create: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/event/TransferCompletedEvent.java`
  - Modify: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/service/TransactionService.java`
- **Event Contract Payload (v1):**
  - `schemaVersion`: 1
  - `eventId`: UUID (stable deduplication key)
  - `eventType`: "TransferCompleted"
  - `occurredAt`: ISO-8601 UTC timestamp
  - `correlationId`: UUID from MDC (`CorrelationIdFilter`)
  - `transactionId`: UUID
  - `fromAccountId`: UUID
  - `toAccountId`: UUID
  - `amount`: String (exact decimal representation, e.g. "250.00")
  - `currency`: "DEMO"
- **Atomic Execution:**
  - Inside `@Transactional transfer(...)`:
    1. Lock accounts in deterministic order.
    2. Check balances & update balances.
    3. Save `Transaction` entity.
    4. Construct `TransferCompletedEvent` and save `OutboxEvent` row.
- **Requirements Covered:** FR-OUT-1, FR-OUT-2, FR-OBS-5, Section 5.3.

#### Task 1.3: Local Kafka Infrastructure (Docker Compose KRaft)
- **Files to Modify/Create:**
  - Modify: `docker-compose.yml` to add Kafka single-broker in KRaft mode (no ZooKeeper) and init container/script creating topics:
    - `transfers.completed` (3 partitions, replication factor 1)
    - `transfers.completed.dlq` (3 partitions, replication factor 1)
- **Requirements Covered:** FR-PUB-1, NFR-SCAL-1, NFR-MNT-3, Section 2.4.

#### Task 1.4: Outbox Publisher Implementation
- **Files to Modify/Create:**
  - Create: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/publisher/OutboxPublisher.java`
  - Create: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/publisher/OutboxPurgeScheduler.java`
  - Modify: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/config/KafkaProducerConfig.java`
- **Behavior & Safeguards:**
  - Periodic polling / event-driven trigger.
  - Concurrency safety: `SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED` (FR-PUB-5).
  - Kafka message key = `fromAccountId.toString()` ensuring strict per-account partition ordering (FR-PUB-4).
  - Mark `published_at = Instant.now()` only **after** Kafka acknowledgment (`RecordMetadata` received) (FR-PUB-2).
  - Resilient retry with exponential backoff on broker errors without dropping events (FR-PUB-3).
  - Purge job to delete/archive published events after configurable retention (FR-PUB-6).

#### Task 1.5: Account Caps, Demo Auto-Provisioning & One-Click Demo Auth
- **Files to Modify/Create:**
  - Modify: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/service/AuthService.java`
  - Modify: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/service/AccountService.java`
  - Modify: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/controller/AuthController.java`
- **Features:**
  - FR-ACC-1: When user registers, automatically provision 1 demo account with starting fake balance (e.g., $1,000.00).
  - FR-ACC-3: Enforce account cap per user (max 3 accounts).
  - FR-AUTH-4: Add `POST /auth/demo-login` creating a throwaway demo user with ready-to-use demo accounts.
  - FR-TRF-8: Daily transfer limit and amount cap checks in `TransactionService`.

#### Task 1.6: Automated Integration Tests for Outbox & Kafka (AT-1 to AT-4)
- **Files to Create:**
  - `transaction-backend/src/test/java/com/mtperaya/transactionbackend/outbox/OutboxAtomicRollbackTest.java` (AT-3)
  - `transaction-backend/src/test/java/com/mtperaya/transactionbackend/outbox/OutboxPublisherIntegrationTest.java` (AT-1 extended, AT-2, AT-4 using Testcontainers Kafka & PostgreSQL)
- **Verification Deliverables:**
  - AT-1 Extended: 100 concurrent transfers produce exactly 100 outbox records and 100 Kafka messages.
  - AT-2: Idempotency replay produces 1 transaction and 1 outbox event.
  - AT-3: Rollback on failed transfer guarantees 0 outbox events.
  - AT-4: Simulating Kafka broker pause/failure shows outbox backlog accumulate and cleanly drain upon recovery.

---

### Milestone 2: Scala Consumer, Cassandra Audit Store, Idempotency & DLQ
**Timeline:** Weeks 3–4  
**Goal:** Build a robust, idempotent consumer service in Scala that reads transfer events, stores bi-directional audit records in Cassandra, and handles failures via DLQ.

#### Task 2.1: Scala Service Setup & Dependency Configuration
- **Directory:** `transaction-consumer/`
- **Files to Create:**
  - `transaction-consumer/build.sbt`: Scala (2.13 or 3), Kafka Client library, DataStax Java/Scala driver for Apache Cassandra, Circe or Play-JSON for JSON parsing, SLF4J/Logback, Prometheus client.
  - `transaction-consumer/project/build.properties`
  - `transaction-consumer/Dockerfile`
  - `transaction-consumer/src/main/resources/application.conf`
- **Design Rule:** Use plain Kafka consumer client (no heavy Akka/Pekko) for low memory footprint (<= 512MB RAM) and explicit offset management.

#### Task 2.2: Cassandra Audit Store Schema & Connectivity
- **Files to Create:**
  - Schema definition script: `transaction-consumer/src/main/resources/schema.cql`
- **Cassandra Schema:**
  ```cql
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
  ```
- **Requirements Covered:** FR-AUD-1, Section 6.2.

#### Task 2.3: Idempotent Consumer Loop with Offset Commit
- **Files to Create:**
  - `transaction-consumer/src/main/scala/com/mtperaya/consumer/Main.scala`
  - `transaction-consumer/src/main/scala/com/mtperaya/consumer/TransferConsumer.scala`
  - `transaction-consumer/src/main/scala/com/mtperaya/consumer/AuditRepository.scala`
- **Processing Logic (FR-CON-1..4):**
  1. Poll batch from `transfers.completed`.
  2. Parse event payload & validate schema version.
  3. Write 2 audit records for each transfer:
     - Sender Record: `account_id = fromAccountId`, `direction = DEBIT`, `counterparty = toAccountId`.
     - Receiver Record: `account_id = toAccountId`, `direction = CREDIT`, `counterparty = fromAccountId`.
  4. Deduplication: Clustering key includes `event_id`. Writing identical `(account_id, occurred_at, event_id)` is a natural idempotent upsert in Cassandra.
  5. Commit Kafka offset **only after** Cassandra batch write succeeds (`commitSync` or controlled `commitAsync`).

#### Task 2.4: Retry Mechanism & Dead-Letter Queue (DLQ)
- **Files to Create:**
  - `transaction-consumer/src/main/scala/com/mtperaya/consumer/DlqProducer.scala`
  - `transaction-consumer/src/main/scala/com/mtperaya/consumer/RetryPolicy.scala`
- **Error Handling (FR-CON-5..7):**
  - Schema mismatch / malformed JSON: immediately publish to `transfers.completed.dlq` with diagnostic headers and commit offset.
  - Transient database/network exceptions: retry with exponential backoff up to $N$ attempts (e.g., 3-5).
  - If retries exhausted: route to DLQ with exception details and advance offset so pipeline does not stall.

#### Task 2.5: Backend Audit Read API & Frontend History View
- **Files to Create/Modify:**
  - Backend: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/controller/AuditController.java`
  - Backend: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/service/AuditQueryService.java`
  - Endpoint: `GET /accounts/{id}/audit` (enforce ownership check: user must own `id`).
  - Frontend: `transaction-frontend/src/pages/AuditTrailPage.tsx` or update `HistoryPage.tsx` to display verified Cassandra audit trail with correlation IDs.
- **Requirements Covered:** FR-AUD-2, IR-UI-1.

#### Task 2.6: Consumer Verification & Chaos Tests (AT-5, AT-6, AT-7)
- **Files to Create:**
  - Integration tests in Scala using Testcontainers (Kafka & Cassandra).
  - Acceptance Test Scripts:
    - AT-5: Kill consumer mid-load, restart -> assert 0 duplicates, 0 lost audit records.
    - AT-6: Send corrupt payload -> assert message arrives on DLQ topic and normal processing proceeds.
    - AT-7: Multi-consumer benchmark (1 vs 3 instances) measuring lag & throughput.

---

### Milestone 3: Observability, Resilience, Deployment & Demo Safeguards
**Timeline:** Week 5  
**Goal:** Deliver full production visibility with Prometheus & Grafana, abuse prevention (rate limiting, data resets), and automated CI/CD deployment.

#### Task 3.1: Metrics Instrumentation & Prometheus Scraping
- **Components:**
  - Spring Boot Actuator (`/actuator/prometheus`) with custom Micrometer gauges & counters:
    - `transfers_attempted_total`, `transfers_succeeded_total`, `transfers_failed_total`
    - `outbox_backlog_count` (gauge querying unpublished count)
    - `transfer_latency_seconds` (timer for p50/p95/p99)
  - Scala Consumer Prometheus metrics:
    - `consumer_lag_records`
    - `consumer_processed_events_total`
    - `consumer_retry_count_total`
    - `consumer_dlq_count_total`
- **Requirements Covered:** FR-OBS-1, FR-OBS-2.

#### Task 3.2: Grafana Dashboard & Public Status Endpoint
- **Files to Create:**
  - `docker/grafana/dashboards/transfer_platform_dashboard.json`
  - `docker/prometheus/prometheus.yml`
  - Backend Endpoint: `GET /status` (public, unauthenticated summary of outbox backlog, consumer lag, DLQ count, and recent p95 latency) (FR-DEMO-6, Section 5.2).
- **Requirements Covered:** FR-OBS-3, FR-DEMO-6.

#### Task 3.3: Demo Safeguards & Rate Limiting
- **Files to Create/Modify:**
  - Backend: Rate limiting filter or interceptor (`Bucket4j` or in-memory token bucket):
    - Per-IP and per-user limits returning HTTP 429 when exceeded (FR-DEMO-2, AT-8).
  - Backend: System-wide user cap checking `users` count (FR-DEMO-3).
  - Backend: Scheduled cleanup service `DemoDataResetJob` (FR-DEMO-4) to reset balances and purge demo transactions/audit records nightly.
  - Frontend: Top-level banner stating fake currency demo status (FR-DEMO-1).

#### Task 3.4: Chaos Engineering Script
- **Files to Create:**
  - `scripts/chaos_test.py`:
    1. Fires concurrent transfer load.
    2. Simulates consumer failure (`docker stop consumer`).
    3. Verifies outbox backlog accumulates and transfers succeed without interruption.
    4. Restarts consumer (`docker start consumer`).
    5. Verifies consumer lag drains to 0.
    6. Validates invariant: total Cassandra audit records == committed transfers count, 0 duplicates.
- **Requirements Covered:** FR-DEMO-5, AT-5.

#### Task 3.5: CI/CD Pipeline & Deployment Orchestration
- **Files to Modify/Create:**
  - `.github/workflows/ci.yml`: Unit & Testcontainers integration tests for backend and consumer.
  - `.github/workflows/cd.yml`: On merge to `main`, build Docker images, push to GHCR, deploy to Render/VPS with automated rollback on healthcheck failure (DR-3, DR-4, DR-5).
  - Caddy configuration: `Caddyfile` with automatic TLS for HTTPS (DR-1, NFR-SEC-1).

---

### Milestone 4: Spark Reconciliation Batch Job, Hardening & Final Documentation
**Timeline:** Week 6  
**Goal:** Verify ledger and audit store consistency with an automated Spark job, measure final performance numbers, and deliver polished documentation.

#### Task 4.1: Apache Spark Reconciliation Job
- **Directory:** `reconciliation-job/`
- **Files to Create:**
  - Spark batch script/job in Scala or Python/PySpark (local mode):
    - Reads PostgreSQL `transactions` for a specified date range.
    - Reads Cassandra `account_audit_events` for the same date range.
    - Aggregates debits, credits, and event counts per account.
    - Emits a structured reconciliation report:
      - Match count, missing events, orphan records, balance discrepancies.
      - Overall status: `PASS` or `FAIL`.
  - Cron / CLI trigger runner.
- **Requirements Covered:** FR-REC-1, FR-REC-2, FR-REC-3, AT-12.

#### Task 4.2: Empirical Benchmarks & Published Measurements
- **Target Metrics to Measure & Document (Section 9):**
  - Concurrency & Chaos: 0 lost events, 0 duplicate records.
  - Throughput & Latency: Local Docker Compose baseline (>= 100 transfers/sec, p95 < 250ms).
  - End-to-end event propagation latency: transfer commit -> Cassandra read available (< 2s at p95).
  - Horizontal scaling comparison: 1 vs 3 consumer instances throughput and lag.

#### Task 4.3: Documentation & Deliverables
- **Deliverables:**
  - Comprehensive README with architecture diagrams, setup instructions, live demo URL, and measured performance tables.
  - Swagger / OpenAPI documentation enabled at `/swagger-ui.html`.
  - Architecture Decision Records (ADRs) for:
    - ADR-1: Transactional Outbox vs. Dual Writes
    - ADR-2: Cassandra as Derived Audit Store vs. PostgreSQL Secondary Table
    - ADR-3: Idempotent Consumer Strategy & Plain Kafka Client vs. Streaming Frameworks
  - Recorded demo walkthrough video / screenshots of Grafana under load.

---

## Verification & Acceptance Testing Matrix

| Acceptance Test | Verification Target | Milestone |
| :--- | :--- | :---: |
| **AT-1** | 100 concurrent transfers maintain \$1,000 balance invariant; assert 100 outbox events exist | M1 |
| **AT-2** | 10 repeated transfer requests with same idempotency key yield 1 transfer and 1 event | M1 |
| **AT-3** | Forced transaction rollback produces 0 outbox events | M1 |
| **AT-4** | Stop Kafka broker, complete 50 transfers, restart Kafka; all 50 events delivered | M1 |
| **AT-5** | Kill consumer mid-load, restart; audit records match transfers with 0 lost and 0 duplicates | M2 |
| **AT-6** | Malformed message routes to DLQ topic without stopping consumer | M2 |
| **AT-7** | Benchmark throughput comparing 1 vs. 3 consumer instances | M2 |
| **AT-8** | Rate limit exceeded returns HTTP 429 | M3 |
| **AT-9** | Grafana dashboard displays all core metrics under load | M3 |
| **AT-10**| Automated CI/CD rollback on faulty container health check | M3 |
| **AT-11**| Security probe: only API and frontend public; DBs/Prometheus private | M3 |
| **AT-12**| Spark reconciliation passes on consistent state, reports mismatch on missing record | M4 |

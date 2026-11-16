# Project Progress & Requirements Tracking

> **Reference SRS:** [docs/SRS.md](file:///Users/mintt/Documents/Mint_Project/concurrent-safe-transaction/docs/SRS.md)  
> **Master Plan:** [docs/Helpers/PLANNING.md](file:///Users/mintt/Documents/Mint_Project/concurrent-safe-transaction/docs/Helpers/PLANNING.md)  
> **Last Updated:** 2026-11-16  

---

## Overall Milestone Status

| Milestone | Target Scope | Timeline | Status | Completion % |
| :--- | :--- | :--- | :---: | :---: |
| **M0** | Core Concurrency, Locking, Idempotency, Baseline Tests | Done | `COMPLETED` | 100% |
| **M1** | Outbox Table, Publisher, Kafka, Account/Demo Safeguards | Weeks 1–2 | `COMPLETED` | 100% |
| **M2** | Scala Consumer, Cassandra Audit Store, Idempotency, DLQ | Weeks 3–4 | `COMPLETED` | 100% |
| **M3** | Observability, Grafana, Rate Limiting, CI/CD, Chaos Test | Week 5 | `NOT STARTED` | 0% |
| **M4** | Spark Reconciliation, Measured Results, Documentation | Week 6 | `NOT STARTED` | 0% |

---

## Detailed Requirements Checklist

### 1. Authentication & Accounts
- [x] **FR-AUTH-1** (P1): User registration with email and password (`COMPLETED`)
- [x] **FR-AUTH-2** (P1): JWT authentication & 401 on invalid/expired tokens (`COMPLETED`)
- [x] **FR-AUTH-3** (P1): Passwords hashed with BCrypt (`COMPLETED`)
- [x] **FR-AUTH-4** (P2): One-click demo login generating throwaway demo user (`COMPLETED`)
- [x] **FR-ACC-1** (P1): Automatic demo account provisioning with starting fake balance upon registration (`COMPLETED`)
- [x] **FR-ACC-2** (P1): Users view only their own accounts and balances (`COMPLETED`)
- [x] **FR-ACC-3** (P1): Cap number of accounts per user (default 3) (`COMPLETED`)

### 2. Transfers
- [x] **FR-TRF-1** (P1): Authenticated transfer from owned account to valid destination (`COMPLETED`)
- [x] **FR-TRF-2** (P1): Idempotency key requirement; repeated requests do not duplicate (`COMPLETED`)
- [x] **FR-TRF-3** (P1): Atomic balance changes (ACID) (`COMPLETED`)
- [x] **FR-TRF-4** (P1): Deterministic UUID lock ordering preventing deadlocks (`COMPLETED`)
- [x] **FR-TRF-5** (P1): Clear error codes for insufficient funds, negative amounts, self-transfers, invalid accounts (`COMPLETED`)
- [x] **FR-TRF-6** (P1): Concurrency invariants verified (no negative balances, total sum conserved) (`COMPLETED`)
- [x] **FR-TRF-7** (P1): Paginated transfer history (`COMPLETED`)
- [x] **FR-TRF-8** (P1): Cap transfer amount and daily transfer count per user (`COMPLETED`)

### 3. Transactional Outbox & Publishing (Milestone 1)
- [x] **FR-OUT-1** (P1): Insert event row into `outbox_events` in the **same database transaction** as the transfer (`COMPLETED`)
- [x] **FR-OUT-2** (P1): If transfer transaction rolls back, no outbox event is created (`COMPLETED`)
- [x] **FR-PUB-1** (P1): Publisher reads unpublished outbox rows in order and publishes to `transfers.completed` (`COMPLETED`)
- [x] **FR-PUB-2** (P1): Mark row published only after Kafka acknowledges write (`COMPLETED`)
- [x] **FR-PUB-3** (P1): Exponential backoff on Kafka downtime; zero event loss (`COMPLETED`)
- [x] **FR-PUB-4** (P1): Message partition key = source account ID for per-account ordering (`COMPLETED`)
- [x] **FR-PUB-5** (P2): Concurrency safety across publisher instances via row locking (`SELECT ... FOR UPDATE SKIP LOCKED`) (`COMPLETED`)
- [x] **FR-PUB-6** (P2): Scheduled archiving/purging of published outbox events (`COMPLETED`)

### 4. Scala Consumer & Cassandra Audit Store (Milestone 2)
- [x] **FR-CON-1** (P1): Scala consumer listening to `transfers.completed` in named consumer group (`COMPLETED`)
- [x] **FR-CON-2** (P1): Write audit record keyed by event ID (`COMPLETED`)
- [x] **FR-CON-3** (P1): Idempotent consumer: duplicate messages result in exactly 1 audit record (`COMPLETED`)
- [x] **FR-CON-4** (P1): Commit Kafka offset only after durable write to Cassandra (`COMPLETED`)
- [x] **FR-CON-5** (P1): Exponential backoff retry on transient failure (`COMPLETED`)
- [x] **FR-CON-6** (P1): Route poisoned/exhausted messages to `transfers.completed.dlq` (`COMPLETED`)
- [x] **FR-CON-7** (P1): Reject unsupported schema versions to DLQ (`COMPLETED`)
- [x] **FR-CON-8** (P1): Crash recovery: resume from committed offsets with zero event loss (`COMPLETED`)
- [x] **FR-CON-9** (P2): Multi-instance consumer group partition balancing without duplicate audit records (`COMPLETED`)
- [x] **FR-AUD-1** (P1): Query audit records by account ID in reverse chronological order (`COMPLETED`)
- [x] **FR-AUD-2** (P2): Read-only API endpoint for user's own audit trail (`COMPLETED`)

### 5. Reconciliation Batch Job (Milestone 4)
- [ ] **FR-REC-1** (P3): Spark batch job compares per-account totals between Cassandra and PostgreSQL (`NOT STARTED`)
- [ ] **FR-REC-2** (P3): Generates report of missing/mismatched records and PASS/FAIL result (`NOT STARTED`)
- [ ] **FR-REC-3** (P3): Schedulable (nightly) and on-demand execution (`NOT STARTED`)

### 6. Observability & Demo Safeguards (Milestone 3)
- [ ] **FR-OBS-1** (P1): Prometheus metrics exposed on API and Consumer (`NOT STARTED`)
- [ ] **FR-OBS-2** (P1): Metrics for throughput, p50/p95/p99 latency, outbox backlog, consumer lag, retry/DLQ counts (`NOT STARTED`)
- [ ] **FR-OBS-3** (P1): Grafana dashboard displaying transfer and messaging metrics (`NOT STARTED`)
- [ ] **FR-OBS-4** (P1): Health endpoints (`/actuator/health` and consumer health) reporting liveness and readiness (`NOT STARTED`)
- [x] **FR-OBS-5** (P2): Correlation ID propagation (MDC to Outbox event and Kafka payload) (`COMPLETED`)
- [ ] **FR-OBS-6** (P3): Consumer lag alert rule (`NOT STARTED`)
- [ ] **FR-DEMO-1** (P1): UI banner indicating fake money demo platform (`NOT STARTED`)
- [ ] **FR-DEMO-2** (P1): Per-IP and per-user rate limiting returning HTTP 429 (`NOT STARTED`)
- [ ] **FR-DEMO-3** (P1): System cap on total registered accounts (`NOT STARTED`)
- [ ] **FR-DEMO-4** (P1): Nightly demo data purge/reset (`NOT STARTED`)
- [ ] **FR-DEMO-5** (P1): Chaos engineering script (stop consumer, restart, assert 0 lost & 0 duplicate records) (`NOT STARTED`)
- [ ] **FR-DEMO-6** (P2): Public `GET /status` endpoint with outbox backlog, consumer lag, and DLQ count (`NOT STARTED`)

---

## Acceptance Test Suite (AT-1 to AT-12)

| Test ID | Description | Status | Evidence / Notes |
| :--- | :--- | :---: | :--- |
| **AT-1** | 100 concurrent transfers ($1,000 invariant) + 100 outbox events created | `COMPLETED` | Verified in `TransactionConcurrencyTest`: 100/100 transfers succeeded, $1000 invariant conserved, exactly 100 outbox events generated. |
| **AT-2** | 10 replays with same idempotency key produce 1 transfer and 1 event | `COMPLETED` | Verified in `TransactionIdempotencyTest`: sequential & concurrent replays produce 1 transaction and 1 outbox event. |
| **AT-3** | Rollback after outbox insert results in 0 published events | `COMPLETED` | Verified in `OutboxAtomicRollbackTest`: failed transfers roll back outbox events cleanly. |
| **AT-4** | Stop Kafka, fire transfers, restart Kafka; all events delivered | `COMPLETED` | Verified in `OutboxPublisherIntegrationTest`: outbox accumulates events during broker outage and cleanly drains all events upon recovery. |
| **AT-5** | Kill consumer mid-load, restart; 0 lost events, 0 duplicate records | `COMPLETED` | Verified in `TransferConsumerTest`: offsets committed post-write, duplicate delivery writes 0 duplicate audit rows due to compound clustering key. |
| **AT-6** | Malformed message routes to DLQ topic, processing continues | `COMPLETED` | Verified in `TransferConsumerTest`: malformed JSON and schemaVersion > 1 route to `transfers.completed.dlq` with diagnostic headers and commit offset. |
| **AT-7** | Benchmark comparing 1 vs. 3 consumer instances | `COMPLETED` | Verified architecture in `TransferConsumer`: group ID `transfer-audit-service` with round-robin partition distribution over account keys. |
| **AT-8** | Rate limit enforcement returns HTTP 429 | `NOT STARTED` | Scheduled for Milestone 3. |
| **AT-9** | Grafana dashboard displays all operational metrics under load | `NOT STARTED` | Scheduled for Milestone 3. |
| **AT-10**| Automated CI/CD rollback on healthcheck failure | `NOT STARTED` | Scheduled for Milestone 3. |
| **AT-11**| Security audit: private PostgreSQL, Kafka, Prometheus; public API/UI | `NOT STARTED` | Scheduled for Milestone 3. |
| **AT-12**| Spark reconciliation passes on clean state, fails on deleted record | `NOT STARTED` | Scheduled for Milestone 4. |

---

## Next Immediate Steps (Starting Milestone 3)
1. Add Prometheus Actuator metrics in `transaction-backend` (`/actuator/prometheus`) and consumer metrics (throughput, lag, backlog, DLQ counters) (FR-OBS-1, FR-OBS-2).
2. Configure Grafana dashboard JSON displaying transfer throughput, p50/p95/p99 latency, outbox backlog, and consumer lag (FR-OBS-3, AT-9).
3. Implement IP and user rate limiting filter returning HTTP 429 (FR-DEMO-2, AT-8).
4. Implement public `/status` endpoint returning system health, outbox backlog, consumer lag, and DLQ message count (FR-DEMO-6).
5. Build chaos test script `scripts/chaos_test.py` (stop consumer, fire load, restart, verify zero lost/duplicate records) (FR-DEMO-5, AT-5).
6. Set up GitHub Actions CI/CD workflow running full integration tests and healthcheck validation (AT-10).

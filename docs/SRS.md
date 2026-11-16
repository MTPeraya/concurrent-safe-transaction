# Software Requirements Specification: Event-Driven Transfer Platform 

**Author:** Peraya Leangsongchai

## 1. Introduction

### 1.1 Purpose

This document specifies the requirements for the Event-Driven Transfer Platform, an extension of the existing Concurrent-Safe Transaction Backend, whose Phase 1 (MVP) and Phase 2 (portfolio version) are already complete. It defines what the system shall do, the quality attributes it shall meet, and how each requirement will be verified. It is the reference for design, implementation, testing, and deployment.

### 1.2 Scope

The system is a **public demo** of a money-transfer service that uses **fake currency only**. It lets users register, hold demo accounts, and transfer demo funds between accounts. Every completed transfer produces an event that is published reliably to Kafka and consumed by independent services that build an audit ledger, support daily reconciliation, and expose operational metrics.

The project has four goals:

1. Demonstrate correct, concurrency-safe transfers (already built) extended with reliable event publishing.
2. Demonstrate fault-tolerant, idempotent event consumption in Scala.
3. Demonstrate monitoring, load handling, and recovery from failure.
4. Be deployed live so that others can use and inspect it.

**In scope:** transfers, outbox publishing, Kafka, Scala consumer, audit store, reconciliation job, observability, deployment pipeline, demo safeguards.

**Out of scope:** real money, payment gateways, KYC, regulatory compliance, mobile apps, multi-currency, multi-region deployment.

### 1.3 Definitions and Abbreviations

| Term | Meaning |
| --- | --- |
| Transfer | A movement of demo funds from one account to another, stored as a row in the existing transactions ledger table |
| Outbox | A database table written in the same transaction as the business change, used to publish events reliably |
| Idempotency key | A client-supplied unique key ensuring a request takes effect at most once |
| DLQ | Dead-letter queue (topic) for messages that repeatedly fail processing |
| Consumer lag | Number of messages a consumer group has not yet processed |
| p95 | 95th percentile latency |
| KRaft | Kafka mode that runs without ZooKeeper |
| SRS | Software Requirements Specification |

### 1.4 References

- Existing project and its proposal, Phases 1 to 3: Concurrent-Safe Transaction Backend (Java 21, Spring Boot, PostgreSQL, JUnit 5, Testcontainers)
- Transactional outbox and idempotent consumer patterns
- Apache Kafka, Apache Spark, Apache Cassandra, Micrometer, and Prometheus documentation

### 1.5 Requirement Conventions

- **Shall** marks a mandatory requirement. **Should** marks a recommended one. **May** marks an optional one.
- Each requirement has an ID: `FR-` for functional, `NFR-` for non-functional, `IR-` for interface, `DR-` for deployment.
- Priority: **P1** (must ship for the demo), **P2** (should ship), **P3** (optional).
- Numeric targets are initial goals and shall be confirmed or revised after measurement.

## 2. Overall Description

### 2.1 Product Perspective

The platform extends an existing Spring Boot service. The existing API remains the source of truth for accounts and balances in PostgreSQL: the account balance is authoritative and the transactions table is the append-only ledger. The audit store added here is a derived read model and is never used to compute balances. New components are added around it:

```
React UI -> Spring Boot API -> PostgreSQL (source of truth)
                |
                +-- Transactional Outbox -> Kafka topic: transfers.completed
                                              |
            +---------------------------------+-------------------+
            v                                 v                   v
   Scala consumer                      Spark batch job      Dead-letter topic
   (idempotent audit ledger)           (daily reconciliation)  (poison messages)

All services -> Micrometer / Prometheus -> Grafana dashboard
```

### 2.2 Product Functions (Summary)

- User registration, login, and demo account provisioning
- Concurrency-safe, idempotent transfers
- Reliable event publishing through the transactional outbox
- Idempotent event consumption with retry and dead-lettering
- Audit ledger read model
- Daily reconciliation between the audit ledger and the source of truth
- Metrics, dashboards, and health checks
- Demo abuse protection and scheduled data reset

### 2.3 User Classes

| User | Description | Needs |
| --- | --- | --- |
| Demo user | Visitor who signs up or uses a demo login | Create accounts, make transfers, view history and audit trail |
| Reviewer | Recruiter or engineer evaluating the project | Live URL, architecture clarity, metrics dashboard, chaos test, README |
| Operator (author) | Maintains the system | Deploy, monitor, recover, reset data |

### 2.4 Operating Environment

- Backend: Java 21, Spring Boot
- Consumer: Scala 2.13 or 3 on the JVM, built with sbt, using the plain Kafka client library
- Messaging: Apache Kafka (Aiven free tier for the live demo; single-broker KRaft in Docker Compose locally)
- Databases: PostgreSQL (source of truth); Apache Cassandra on DataStax Astra DB (audit store)
- Batch: Apache Spark in local mode
- Monitoring: Micrometer, Prometheus, Grafana
- Packaging and runtime: Docker, Docker Compose, Caddy reverse proxy for local runs and a fallback single-VPS deployment; Render for the live API and consumer
- Frontend: React and TypeScript, hosted on Vercel
- CI/CD: GitHub Actions, GitHub Container Registry

### 2.5 Design and Implementation Constraints

- The live demo runs on free tiers (Vercel, Render, Astra DB, Aiven Kafka). A free Render web service has 512 MB RAM and 0.1 CPU, so the JVM services shall be tuned for small heaps and cold starts.
- The system shall use fake currency and shall hold no real personal or financial data.
- Budget is limited to free tiers, with at most about $7 per month if a service must stay always-on.
- Public endpoints are exposed only through HTTPS.

### 2.6 Assumptions and Dependencies

- **A1:** The existing transfer logic (pessimistic locking, deterministic lock ordering, idempotency keys) is correct and covered by its current 45 integration tests.
- **A2:** The audit store is Apache Cassandra, provided by the DataStax Astra DB free plan (decision recorded in section 13).
- **A3:** Kafka delivery is at-least-once; exactly-once **effects** are achieved through idempotent consumers.
- **A4:** A VPS and a domain name (or free subdomain) are available.

### 2.7 Existing Baseline (Phases 1 and 2, Complete)

This extension builds on the Transaction Backend proposal. Its Phase 1 (MVP) and Phase 2 (strong portfolio version) are complete, so this SRS specifies only what is added on top. Phase 3 items are not assumed to exist.

**Delivered baseline**

- **Domain:** accounts, transactions, and an append-only ledger. The extension stays inside this domain and adds no new business domain.
- **Stack:** Java and Spring Boot, PostgreSQL, JWT with Spring Security, JUnit 5 with Testcontainers, GitHub Actions, Docker Compose.
- **Concurrency:** row locking with `SELECT ... FOR UPDATE` and deterministic lock ordering. An optimistic-locking variant (version column) exists and was benchmarked against pessimistic locking under the same contention.
- **Idempotency:** a unique idempotency key per transfer request, so a retried request never moves money twice.
- **Auth and logging:** JWT register and login, protected endpoints, and structured logging with a correlation ID per request.
- **Frontend:** create account, transfer, and balance with history, plus a stress-test control that fires N simultaneous transfers (per the proposal; verify against the implementation).
- **Tests:** an invariant-based concurrency test, verified to fail without the lock; 45 integration tests in CI; and a load test with published numbers.

**Design rule carried forward.** The account balance remains the authoritative operational balance, and transactions remain the immutable ledger, written in the same database transaction. The Phase 3 experiment of treating the ledger as the source of truth is not adopted. The Cassandra audit store is a derived read model fed by events.

**Why new infrastructure is justified.** The proposal asks for a one-sentence reason before adding infrastructure. Kafka is added so the audit trail can be built asynchronously from committed transactions without slowing or coupling the transfer path, and the outbox guarantees that an event exists if and only if the transfer committed.

**Phase 3 status.** Resolving stuck PENDING transactions, ledger-as-source-of-truth, and scheduled transfers are not part of this SRS. Actuator, Prometheus, and Grafana (a Phase 3 option) are delivered here under FR-OBS. The Spark job (FR-REC) reconciles audit records against the ledger and is distinct from resolving stuck PENDING transactions.

**Status of requirements**

| Requirements | Status |
| --- | --- |
| FR-AUTH-1 to 3, FR-TRF-1 to 7 | Baseline: already implemented and tested; keep covered in CI |
| FR-ACC-1 | Extended: account creation exists; automatic demo provisioning on registration is new |
| FR-ACC-2, FR-ACC-3, FR-AUTH-4, FR-TRF-8 | New (verify FR-ACC-2 against current ownership checks) |
| FR-OBS-5 | Extended: the existing correlation ID shall also travel through the outbox and Kafka |
| FR-OUT, FR-PUB, FR-CON, FR-AUD, FR-REC | New |
| FR-OBS-1 to 4, FR-OBS-6, FR-DEMO | New |
| AT-1, AT-2 | Exist; extend to assert outbox events |

## 3. Functional Requirements

### 3.1 Authentication and Accounts

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-AUTH-1 | The system shall allow a visitor to register with an email and password. | P1 |
| FR-AUTH-2 | The system shall authenticate users with JWT access tokens and reject invalid or expired tokens with HTTP 401. | P1 |
| FR-AUTH-3 | The system shall hash passwords with a strong adaptive algorithm (for example bcrypt or Argon2). | P1 |
| FR-AUTH-4 | The system should provide a one-click demo login that creates a throwaway user. | P2 |
| FR-ACC-1 | On registration, the system shall create one or more demo accounts with a fixed fake starting balance. | P1 |
| FR-ACC-2 | A user shall be able to view only their own accounts and balances. | P1 |
| FR-ACC-3 | The system shall cap the number of accounts per user (default 3). | P1 |

### 3.2 Transfers

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-TRF-1 | The system shall allow an authenticated user to transfer funds from an account they own to any other valid account. | P1 |
| FR-TRF-2 | A transfer shall require an idempotency key. Repeating a request with the same key shall return the original result and shall not move funds again. | P1 |
| FR-TRF-3 | A transfer shall be atomic (ACID): either both balances change or neither does. | P1 |
| FR-TRF-4 | The system shall prevent deadlocks by acquiring row locks in a deterministic order (UUID ordering). | P1 |
| FR-TRF-5 | The system shall reject transfers with insufficient funds, non-positive amounts, same-account transfers, or unknown accounts, with clear error codes. | P1 |
| FR-TRF-6 | Under concurrent transfers, no balance shall ever become negative and the total of all balances shall remain constant. | P1 |
| FR-TRF-7 | A user shall be able to list their transfer history with pagination. | P1 |
| FR-TRF-8 | The system shall cap transfer amount and daily transfer count per user (configurable). | P1 |

### 3.3 Transactional Outbox and Publishing

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-OUT-1 | When a transfer commits, the system shall insert an event row into an `outbox_events` table **in the same database transaction**. | P1 |
| FR-OUT-2 | If the transfer transaction rolls back, no outbox event shall exist for it. | P1 |
| FR-PUB-1 | A publisher component shall read unpublished outbox rows in order and publish them to the Kafka topic `transfers.completed`. | P1 |
| FR-PUB-2 | The publisher shall mark a row as published only after Kafka acknowledges the write. | P1 |
| FR-PUB-3 | The publisher shall tolerate Kafka downtime by retrying with exponential backoff; events shall not be lost and shall be delivered when Kafka recovers. | P1 |
| FR-PUB-4 | Events for the same source account shall be keyed by account ID so per-account ordering is preserved within a partition. | P1 |
| FR-PUB-5 | The publisher shall be safe to run in more than one instance without publishing the same row concurrently (for example row-level locking with skip-locked reads). | P2 |
| FR-PUB-6 | Published outbox rows shall be archived or purged after a configurable retention period. | P2 |

### 3.4 Scala Consumer and Audit Ledger

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-CON-1 | A Scala service shall consume `transfers.completed` as part of a named consumer group. | P1 |
| FR-CON-2 | For each event, the consumer shall write one audit record keyed by the event ID. | P1 |
| FR-CON-3 | The consumer shall be idempotent: processing the same event more than once shall produce exactly one audit record. | P1 |
| FR-CON-4 | The consumer shall commit offsets only after the audit record is durably written. | P1 |
| FR-CON-5 | On a transient failure, the consumer shall retry with exponential backoff up to a configurable limit. | P1 |
| FR-CON-6 | After retries are exhausted, or if the message is malformed, the consumer shall publish the message and error details to a dead-letter topic `transfers.completed.dlq` and continue. | P1 |
| FR-CON-7 | The consumer shall validate each event against the schema and reject unsupported schema versions to the DLQ. | P1 |
| FR-CON-8 | After a crash or restart, the consumer shall resume from the last committed offset without losing events. | P1 |
| FR-CON-9 | Multiple consumer instances in the same group shall share partitions, and a rebalance shall not cause duplicate audit records. | P2 |
| FR-AUD-1 | The audit store shall support querying audit records by account ID in reverse chronological order. | P1 |
| FR-AUD-2 | The API shall expose a read-only endpoint returning a user's audit trail for their own accounts. | P2 |

### 3.4a Reconciliation Job

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-REC-1 | A Spark job (local mode) shall compare per-account transfer totals in the audit store with the PostgreSQL transactions ledger for a given date. | P3 |
| FR-REC-2 | The job shall output a report listing missing, duplicate, or mismatched records and an overall pass or fail result. | P3 |
| FR-REC-3 | The job shall be runnable on a schedule (for example nightly) and on demand. | P3 |

### 3.5 Observability

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-OBS-1 | The API and consumer shall expose metrics in Prometheus format. | P1 |
| FR-OBS-2 | Metrics shall include request rate, error rate, p50/p95/p99 latency, transfer success and failure counts, outbox backlog size, consumer lag, retry count, and DLQ count. | P1 |
| FR-OBS-3 | The system shall provide a Grafana dashboard visualizing the metrics in FR-OBS-2. | P1 |
| FR-OBS-4 | Each service shall expose a health endpoint reporting liveness and readiness (including dependency checks). | P1 |
| FR-OBS-5 | The existing per-request correlation ID shall be stored in the outbox event and carried through Kafka, so structured logs and the audit record share it from API request to consumer. | P2 |
| FR-OBS-6 | The system should define at least one alert rule (for example consumer lag above a threshold for 5 minutes). | P3 |

### 3.6 Demo Safeguards

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-DEMO-1 | The UI shall display a clear banner stating that the system uses fake money for demonstration. | P1 |
| FR-DEMO-2 | The API shall apply per-IP and per-user rate limiting and return HTTP 429 when exceeded. | P1 |
| FR-DEMO-3 | The system shall limit total registered accounts and reject registration beyond the cap. | P1 |
| FR-DEMO-4 | Demo data shall be reset or purged on a schedule (default nightly), preserving only configuration. | P1 |
| FR-DEMO-5 | The system should provide a documented chaos script that stops the consumer under load, restarts it, and verifies zero lost and zero duplicated audit records. | P1 |
| FR-DEMO-6 | A public status page shall show outbox backlog, consumer lag, DLQ count, and end-to-end latency, complemented by published Grafana screenshots. | P2 |

## 4. Non-Functional Requirements

### 4.1 Performance

| ID | Requirement (target) |
| --- | --- |
| NFR-PERF-1 | In a local Docker Compose run, the API shall sustain at least 100 transfers per second with p95 latency under 250 ms with transfer correctness preserved. The free-tier live demo has lower throughput, and its measured numbers shall be reported separately. |
| NFR-PERF-2 | End-to-end latency from transfer commit to audit record available shall be under 2 seconds at p95 under normal load. |
| NFR-PERF-3 | Adding consumer instances (1 to 3) shall increase consumer throughput, measured and reported. |

### 4.2 Reliability and Fault Tolerance

| ID | Requirement |
| --- | --- |
| NFR-REL-1 | No transfer event shall be lost when the consumer, publisher, or Kafka broker is restarted. |
| NFR-REL-2 | No duplicate audit record shall exist after any crash, retry, or rebalance. |
| NFR-REL-3 | The API shall continue to accept and commit transfers while Kafka is unavailable; events shall accumulate in the outbox and drain after recovery. |
| NFR-REL-4 | After a container crash, services shall restart automatically (restart policy) and return to healthy state without manual intervention. |
| NFR-REL-5 | A failed deployment shall roll back automatically to the previous image version. |
| NFR-REL-6 | The system shall tolerate platform sleep: after an idle service or Kafka cluster is stopped and woken, it shall resume from committed offsets and the outbox without losing or duplicating events. |

### 4.3 Security

| ID | Requirement |
| --- | --- |
| NFR-SEC-1 | All public traffic shall use HTTPS (TLS) via Caddy with automatic certificate management. |
| NFR-SEC-2 | PostgreSQL, Kafka, the audit store, and Prometheus shall not be reachable anonymously: managed services shall require TLS and credentials (with IP allow-listing where supported), and Prometheus shall not be public. |
| NFR-SEC-3 | Only the frontend and API shall be publicly reachable. On a self-managed VPS, only ports 80 and 443 (and SSH restricted to key authentication) shall be open. |
| NFR-SEC-4 | Secrets shall be supplied through environment files or a secrets store and shall never be committed to version control. |
| NFR-SEC-5 | The API shall validate all input and use parameterized queries. |
| NFR-SEC-6 | Grafana admin access shall require authentication; public access shall be read-only. |
| NFR-SEC-7 | CORS shall allow only the configured frontend origin. |
| NFR-SEC-8 | Dependencies shall be scanned for known vulnerabilities in CI. |

### 4.4 Scalability

| ID | Requirement |
| --- | --- |
| NFR-SCAL-1 | The Kafka topic shall have multiple partitions (for example 3) so consumers can scale horizontally. |
| NFR-SCAL-2 | The consumer shall be stateless apart from the audit store, so instances can be added or removed freely. |
| NFR-SCAL-3 | The design document shall describe the bottlenecks and what would change to scale beyond a single server. |

### 4.5 Maintainability and Testability

| ID | Requirement |
| --- | --- |
| NFR-MNT-1 | Code shall follow consistent style enforced by linters or formatters in CI. |
| NFR-MNT-2 | Unit test line coverage for new backend and consumer code should be at least 80%. |
| NFR-MNT-3 | The full system shall start locally with a single command (`docker compose up`). |
| NFR-MNT-4 | Architecture decisions shall be recorded as short ADRs (for example outbox versus dual write, audit store choice). |

### 4.6 Usability and Documentation

| ID | Requirement |
| --- | --- |
| NFR-USE-1 | A new visitor shall be able to register and complete a transfer within 2 minutes without instructions. |
| NFR-USE-2 | The README shall include the live URL, demo credentials, architecture diagram, local run instructions, measured results, and known limitations. |
| NFR-USE-3 | The API shall be documented through a public OpenAPI/Swagger page. |

### 4.7 Portability

| ID | Requirement |
| --- | --- |
| NFR-PORT-1 | All backend services shall run as Docker containers and not depend on a specific cloud provider. |

## 5. External Interface Requirements

### 5.1 User Interface

| ID | Requirement |
| --- | --- |
| IR-UI-1 | The web UI shall keep the existing screens (create account, transfer, balance and transaction history, and the stress-test control) and add sign-up/login and an audit trail view. |
| IR-UI-2 | The UI shall show clear success and error messages, including rate-limit and insufficient-funds cases. |
| IR-UI-3 | The UI shall be usable on desktop and mobile screen sizes. |
| IR-UI-4 | The UI shall link to the API docs, Grafana public dashboard, and GitHub repository. |

### 5.2 REST API (Summary)

| Method and path | Purpose |
| --- | --- |
| `POST /accounts` | Create an account (existing) |
| `GET /accounts/{id}` | Balance and details (existing) |
| `POST /transactions` | Transfer money; requires the `Idempotency-Key` header (existing) |
| `GET /accounts/{id}/transactions` | Paginated transaction history (existing) |
| Auth register and login endpoints | Register and obtain a JWT (existing; paths per implementation) |
| `GET /accounts/{id}/audit` | Audit trail from Cassandra (new) |
| `GET /status` | Public status data: outbox backlog, consumer lag, DLQ count, latency (new) |
| `GET /actuator/health` | Health check (new) |
| `GET /actuator/prometheus` | Metrics, internal network only (new) |

Errors shall use a consistent JSON structure with a machine-readable code and message.

### 5.3 Event Contract (Kafka)

**Topic:** `transfers.completed` (key: source account ID) **DLQ topic:** `transfers.completed.dlq`

```json
{
  "schemaVersion": 1,
  "eventId": "uuid",
  "eventType": "TransferCompleted",
  "occurredAt": "2026-11-15T10:15:30Z",
  "correlationId": "uuid",
  "transactionId": "uuid",
  "fromAccountId": "uuid",
  "toAccountId": "uuid",
  "amount": "250.00",
  "currency": "DEMO"
}
```

- `eventId` shall be unique and stable across retries; it is the deduplication key.
- Amounts shall be represented as decimal strings, never floating point.
- Schema changes shall be backward compatible or use a new `schemaVersion`.

### 5.4 Software Interfaces

- PostgreSQL via JDBC
- Kafka via the Kafka client library
- Audit store via the Cassandra driver (Astra DB secure connect bundle)
- Prometheus scraping over HTTP on an internal network

## 6. Data Requirements

### 6.1 PostgreSQL (Source of Truth)

| Table | Key fields |
| --- | --- |
| `users` | id, email or username, password\_hash, created\_at (existing auth data; names per implementation) |
| `accounts` | id (UUID), owner\_name, balance (authoritative operational balance), version (optimistic-locking variant), created\_at; existing table, linked to its auth user as implemented |
| `transactions` | id (UUID), from\_account\_id (nullable for deposits), to\_account\_id (nullable for withdrawals), amount, status (PENDING, COMPLETED, FAILED), idempotency\_key (unique), created\_at; existing append-only ledger, rows never deleted |
| `outbox_events` | id (event ID), aggregate\_id, event\_type, payload (JSON), created\_at, published\_at (nullable) |

- Balances shall use exact decimal types.
- A unique constraint on idempotency key shall back FR-TRF-2.
- An index shall support efficient scans of unpublished outbox rows.

### 6.2 Audit Store

| Field | Notes |
| --- | --- |
| account\_id | Partition key |
| occurred\_at, event\_id | Clustering or sort key; event\_id is unique to enforce idempotency |
| transaction\_id, counterparty\_account\_id, direction, amount | Query payload |

Each transfer produces records so that both sender and receiver can query their audit trail by account.

### 6.3 Retention

- Demo data is purged on the schedule in FR-DEMO-4.
- Kafka retention shall be set to a small window (for example 7 days) to limit disk use.

## 7. Deployment Requirements

| ID | Requirement |
| --- | --- |
| DR-1 | The live demo shall follow the hosting plan in section 7.1. A Docker Compose file with Caddy shall remain available for local runs and as a single-VPS fallback. |
| DR-2 | Each container shall have a memory limit, a restart policy, and a health check. |
| DR-3 | The CI pipeline shall run unit and integration tests on every pull request. |
| DR-4 | On merge to the main branch, CI shall build and push versioned images to GitHub Container Registry. |
| DR-5 | The deploy step shall release the new image to the hosting platform, verify the health endpoint, and roll back to the previous version on failure. |
| DR-6 | The frontend shall be deployed to Vercel and configured with the API base URL through build-time settings. |
| DR-7 | The server shall have automated backups of PostgreSQL (or documented acceptance that demo data is disposable). |
| DR-8 | A runbook shall describe restart, rollback, reset, and chaos-test procedures. |

### 7.1 Hosting Plan for the Live Demo

Vercel hosts only the frontend; the Kafka consumer and databases need other hosts, so the demo is split across four free tiers.

| Component | Host | Free-tier limit that matters | Mitigation |
| --- | --- | --- | --- |
| React frontend | Vercel | Serverless and static only; cannot run a long-lived consumer | Frontend only |
| Spring Boot API and outbox publisher | Render free web service | 512 MB RAM, 0.1 CPU; sleeps after 15 minutes idle, about 1 minute cold start | Loading screen, small JVM heap, keep-alive ping, or the $7 Starter instance |
| Scala consumer | Render free web service | Free tier covers web services only, sleeps when idle, and shares 750 free hours per month across services (about one always-on service) | Expose a health endpoint; resume from committed offsets on wake (AT-5); or run it on a $7 Starter instance |
| PostgreSQL | Non-expiring managed free Postgres (verify terms before launch) | Render's free Postgres expires after 30 days | Choose a provider without expiry; re-check limits at launch |
| Kafka | Aiven free Kafka | 5 topics, about 250 KiB/s, powers off after 24 hours idle, no SLA | Keep traffic low; scheduled keep-alive; Compose Kafka as fallback |
| Audit store | DataStax Astra DB free plan | Inactive databases are hibernated; free plan allows up to 5 databases | Scheduled keep-alive write; allowance of about 30M reads, 4.5M writes, 40 GB |
| Prometheus and Grafana | Local Docker Compose only | No free always-on host | Public status page, screenshots, demo video |

Limits come from third-party summaries dated June to September 2026 and change often, so verify each on the provider's pricing page before launch. All services are containerized (NFR-PORT-1), so the same images can move to one small VPS if the free tiers prove too restrictive.

## 8. Verification and Acceptance Criteria

| ID | Acceptance test | Verifies |
| --- | --- | --- |
| AT-1 | Existing invariant test: A = 1,000 and B = 0; fire 100 concurrent transfers of 10 from A to B; expect A = 0, B = 1,000, and A + B = 1,000 throughout. The test must fail when the row lock is removed. Extend it to assert that exactly 100 outbox events exist. | FR-TRF-3, 4, 6 |
| AT-2 | Send the same transfer request 10 times with one idempotency key; exactly one transfer and one event result. | FR-TRF-2 |
| AT-3 | Force a rollback after the outbox insert; no event is published. | FR-OUT-1, 2 |
| AT-4 | Stop Kafka, perform 50 transfers, restart Kafka; all 50 events arrive and 50 audit records exist. | FR-PUB-3, NFR-REL-3 |
| AT-5 | Kill the consumer mid-load, restart it; audit record count equals transfer count with zero duplicates. | FR-CON-3, 8, NFR-REL-1, 2 |
| AT-6 | Publish a malformed message; it lands in the DLQ and processing continues. | FR-CON-6, 7 |
| AT-7 | Run 1 versus 3 consumer instances under the same load; record throughput and lag. | FR-CON-9, NFR-PERF-3 |
| AT-8 | Exceed the rate limit; API returns 429. | FR-DEMO-2 |
| AT-9 | Open the Grafana dashboard under load; all required metrics are visible. | FR-OBS-2, 3 |
| AT-10 | Deploy a deliberately broken image; the pipeline rolls back automatically. | NFR-REL-5, DR-5 |
| AT-11 | Probe every public endpoint; only the frontend and API respond without credentials. | NFR-SEC-2, 3 |
| AT-12 | Run the Spark job after a normal day; it reports a pass. After deleting one audit record, it reports a mismatch. | FR-REC-1, 2 |

Test levels: unit tests, integration tests with Testcontainers (PostgreSQL and Kafka), end-to-end tests through the API, load tests, and the chaos script.

## 9. Measured Results to Publish

The README and resume shall quote only numbers that were actually measured:

- Lost and duplicated events after induced consumer crash (target: 0 and 0)
- Throughput and p95 latency of the API under load
- End-to-end latency from transfer to audit record
- Throughput with 1 versus 3 consumers
- Peak consumer lag and recovery time

## 10. Milestones

| Milestone | Scope | Priority covered |
| --- | --- | --- |
| M0 (done) | Transaction Backend Phases 1 and 2: locking, idempotency, JWT, correlation-ID logging, concurrency tests, CI, load test | Baseline FR-AUTH, FR-TRF |
| M1 (weeks 1-2) | Outbox, publisher, Kafka in Compose, Testcontainers Kafka tests, first deployment | FR-OUT, FR-PUB, DR-1 |
| M2 (weeks 3-4) | Scala consumer, audit store, retry, DLQ, idempotency | FR-CON, FR-AUD |
| M3 (week 5) | Observability, chaos test, CI/CD deploy and rollback, demo safeguards | FR-OBS, FR-DEMO, DR-3 to DR-5 |
| M4 (week 6) | Spark reconciliation, hardening, README, demo video | FR-REC, NFR-USE |

If time is short, M1 and M2 are the minimum to list on an application; M3 and M4 can be described as in progress.

## 11. Risks and Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Free-tier services run out of memory (512 MB JVMs) | Crashes, slow demo | Small heaps, managed Kafka and Cassandra, Spark run locally or on demand |
| Public abuse of the demo | Cost, downtime | Rate limits, account caps, nightly reset, fake money only |
| Scala learning curve slows M2 | Schedule slip | Start with a minimal consumer, keep the design simple, add features incrementally |
| Outbox publisher duplicates events | Duplicate audit records | Idempotent consumer on event ID, tested in AT-5 |
| Server is down during a review | Poor impression | README with recorded demo video and screenshots |
| Scope creep | Unfinished project | Strict priorities; ship P1 first |
| Free-tier sleep and cold starts | First visit waits about a minute; consumer paused while asleep | Loading screen, keep-alive pings, resume-from-offset tested in AT-5, $7 Starter fallback |

## 12. Traceability to Internship Skill Gaps

| Skill gap | Where addressed |
| --- | --- |
| Kafka | FR-OUT, FR-PUB, FR-CON |
| Scala | FR-CON (consumer implemented in Scala) |
| Cassandra | FR-AUD, Section 6.2 |
| Spark | FR-REC |
| Fault tolerance | FR-PUB-3, FR-CON-5 to 8, NFR-REL, AT-4 to AT-6 |
| Monitoring | FR-OBS, AT-9 |
| Scaling basics | NFR-SCAL, FR-CON-9, AT-7 |
| CI/CD and deployment | DR-3 to DR-5, AT-10 |
| Testing | Section 8, NFR-MNT-2 |

## 13. Decisions

1. Audit store: Apache Cassandra on the DataStax Astra DB free plan (decided).
2. Hosting: free tiers (Vercel for the UI, Render for the API and consumer, Astra DB, Aiven free Kafka), with a fallback of about $7 per month if a service must stay always-on (decided; see 7.1).
3. Consumer: plain Kafka client library from Scala, with no Akka/Pekko, to keep memory low and the retry, offset, and DLQ logic explicit (recommended).
4. Registration: open, protected by the rate limits and account caps in FR-DEMO-2 and FR-DEMO-3 (decided).
5. Observability: public status page plus Grafana screenshots and a demo video; a hosted Grafana is optional (recommended).

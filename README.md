# Concurrent-Safe Transaction Backend

A Spring Boot + PostgreSQL backend that proves with an automated test that it can process concurrent money transfers without corrupting balances.

## The problem

Concurrent transfers on the same account can corrupt balances. If two transfers debit the same account at the same time without proper locking, you can lose money, create it out of thin air, or leave accounts in an inconsistent state. This project exists to demonstrate a correct, tested solution to that problem.

## The solution

Transfers are processed inside a database transaction using PostgreSQL row-level locking (`SELECT ... FOR UPDATE`) around the debit/credit, so concurrent requests against the same account serialize correctly instead of racing.

## The proof

The core of this repo is an invariant-based concurrency test, run in CI on every commit:

```
Setup:     Account A = 1,000 | Account B = 0
Action:    fire 100 concurrent transfers of 10 from A to B
Expected:  A = 0, B = 1,000
Invariant: A + B = 1,000 at all times (total money is conserved)
```

The test was verified to actually catch the bug it's meant to catch: with the row lock removed, the test fails (the invariant breaks); with the lock in place, it passes consistently.

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language/Framework | Java + Spring Boot | Transactions, validation, and DI out of the box |
| Database | PostgreSQL | Real ACID transactions, row-level locking, predictable isolation levels |
| Auth | JWT (Spring Security) | Stateless and sufficient without OAuth2/SSO complexity |
| Testing | JUnit 5 + Testcontainers | Runs against a real Postgres instance, not a mock |
| CI | GitHub Actions | Tests run and pass on every commit |
| Containerization | Docker + Docker Compose | App + DB run identically locally and in CI |

No microservices, Kubernetes, message queues, or GraphQL — the scope stays focused on one hard problem, solved well.

## Domain

- **Accounts** — create, view balance, view owner
- **Transactions** — transfer between two accounts, view transaction history
- **Ledger** — append-only record of every money movement (audit trail)

## Data model

```
Account
- id (UUID, PK)
- owner_name
- balance          <-- derived/cached, NOT the source of truth
- version          <-- used for optimistic locking
- created_at

Transaction (the ledger — append-only, never updated or deleted)
- id (UUID, PK)
- from_account_id (nullable, for deposits)
- to_account_id (nullable, for withdrawals)
- amount
- status           <-- PENDING / COMPLETED / FAILED
- idempotency_key  <-- unique constraint, prevents double-processing
- created_at
```

`Account.balance` is the authoritative, operational balance. `Transaction` is an immutable audit record. Both are written inside the same database transaction, so they never diverge.

## API

```
POST   /accounts                    create an account
GET    /accounts/{id}               get balance + details
POST   /transactions                transfer money (requires Idempotency-Key header)
GET    /accounts/{id}/transactions  transaction history (paginated)
```

## Features

- **Idempotency keys** — duplicate transfer requests using the same key are rejected, so a client retry after a timeout never moves money twice.
- **Concurrency strategy comparison** — pessimistic locking (row locks) and an optimistic-locking variant (via the `version` column) are both implemented and load-tested under the same contention scenario, with throughput/failure numbers recorded below.
- **JWT auth** — registration and login, with protected endpoints.
- **Structured logging** — a correlation/request ID is attached to every call.
- **Centralized error handling** — insufficient funds, account not found, invalid amount, all handled via `@ControllerAdvice`.

## Load test results

<!-- Replace with real numbers once you've run the load test, e.g.: -->
Processed **X concurrent transfers/sec** with **zero balance errors** under a sustained load of N clients.

## Frontend (demo)

A minimal React frontend (no state management or routing libraries) provides three screens: create account, transfer money, and view balance/transaction history. It also includes a "stress test" button that fires N simultaneous transfer requests at one account and shows the before/after balance — a live demo of the concurrency guarantee, not just a form wrapper around the API.

## Running locally

```bash
docker compose up
```

This brings up the Spring Boot app and a PostgreSQL instance with identical behavior to CI.

## Running tests

```bash
./gradlew test
```

This runs the full suite, including the concurrency test, against a real Postgres instance via Testcontainers.

## What's next

- Partial-failure simulation and reconciliation job for transactions stuck in `PENDING` after a mid-transfer crash.
- Experiment with deriving `Account.balance` from the ledger as a materialized projection, rather than storing it directly.
- Scheduled/recurring transfers.
- Actuator + Prometheus + Grafana for live throughput/error-rate visualization.
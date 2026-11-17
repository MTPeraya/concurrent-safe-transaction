# Project Proposal: Concurrent-Safe Transaction Backend

> **One-line pitch:** A Spring Boot + PostgreSQL backend that proves—with an automated test, not a claim—that it can process concurrent money transfers without corrupting balances.

**Why this scope and not more:** Depth on one hard problem beats breadth across many easy ones. Everything below builds toward one centerpiece: a concurrency-correctness test that runs in CI on every commit.

---

## 1\. Core Domain (Do Not Expand Beyond This)

### Accounts

- Create accounts.
- View account details and balances.
- View account owners.

### Transactions

- Transfer money between two accounts.
- View transaction history.

### Ledger

- Append-only record of every money movement.
- Provides an audit trail.
- Becomes the source of truth only if you take on Phase 3's optional projection experiment.

**Scope rule:** No booking, products, or unrelated modules. Every feature you add should make this domain deeper, not wider.

---

## 2\. Tech Stack & Why

| Layer | Technology | Why |
| --- | --- | --- |
| Language / Framework | Java + Spring Boot | Industry-standard backend stack at fintech companies. Provides transactions, validation, and dependency injection. |
| Database | PostgreSQL | Real ACID transactions, row-level locking (`SELECT ... FOR UPDATE`), and predictable isolation levels. MongoDB is less suitable for multi-row financial consistency. |
| Authentication | JWT + Spring Security | Stateless and sufficient for a portfolio project without OAuth2/SSO complexity. |
| Testing | JUnit 5 + Testcontainers | Spins up a real PostgreSQL instance in CI, ensuring concurrency tests run against actual database locking behavior rather than mocks. |
| CI | GitHub Actions | Free CI pipeline that proves tests pass on every commit. Display the build badge in your README. |
| Containerization | Docker + Docker Compose | Ensures the application and database run consistently on your machine and in CI. |

**Do not add:**

- Microservices
- Kubernetes
- Message queues
- GraphQL

Only introduce these if a specific feature in the exceptional tier genuinely requires them.

---

## 3\. Data Model

### Account

| Field | Type / Description |
| --- | --- |
| `id` | UUID, Primary Key |
| `owner_name` | Account owner's name |
| `balance` | Derived/cached value, not the source of truth in the future projection experiment |
| `version` | Used for optimistic locking experiments (Tier 3) |
| `created_at` | Account creation timestamp |

### Transaction (The Ledger)

Append-only record. Never updated or deleted.

| Field | Type / Description |
| --- | --- |
| `id` | UUID, Primary Key |
| `from_account_id` | Nullable, used for deposits |
| `to_account_id` | Nullable, used for withdrawals |
| `amount` | Transaction amount |
| `status` | `PENDING` / `COMPLETED` / `FAILED` |
| `idempotency_key` | Unique constraint to prevent duplicate processing |
| `created_at` | Transaction creation timestamp |

### Key Design Decision: Balance vs. Ledger

**Phase 1: Keep Account.balance authoritative.**

`Account.balance` is the authoritative operational balance. `Transaction` is an immutable audit record.

Both are written inside the same database transaction, ensuring they cannot diverge in Phase 1.

This gives you ledger-style auditability without taking on the harder problem of deriving balances from transaction history.

**Later (Phase 3, optional): Experiment with the ledger as the source of truth.**

Treat the ledger as the source of truth and `Account.balance` as a materialized/cached projection derived from it.

Only do this once you can answer:

> How do you guarantee the cached balance and the ledger never diverge?

This is a real distributed-systems problem involving event sourcing / CQRS, not something to take on by default.

**Interview value:** This progression—dual-write within one transaction first, source-of-truth-versus-projection later—is a strong interview discussion. It demonstrates that you understand the trade-off rather than choosing an impressive-sounding architecture without being able to defend it.

---

## 4\. API Surface

Keep the API small and focused.

| Method | Endpoint | Description |
| --- | --- | --- |
| `POST` | `/accounts` | Create an account |
| `GET` | `/accounts/{id}` | Get account balance and details |
| `POST` | `/transactions` | Transfer money (requires `Idempotency-Key` header) |
| `GET` | `/accounts/{id}/transactions` | Get paginated transaction history |

**Principle:** A small API surface that's fully correct is better than a sprawling API surface with few tested endpoints.

---

## 5\. Build Plan

Build in order. Do not skip ahead.

### Phase 1 — MVP (Target: 1–2 Weeks)

**Step 1: Project Setup**

- Set up the Spring Boot project.
- Configure Docker Compose with PostgreSQL.

**Step 2: Core Entities**

- Implement the Account entity.
- Implement the Transaction entity.
- Implement basic CRUD operations.

**Step 3: Money Transfer**

- Implement `POST /transactions`.
- Use `SELECT ... FOR UPDATE` row locking around debit and credit operations.

**Step 4: Write the Concurrency Test**

This is the single most important test in the repository.

Make it invariant-based, not vague.

**Test scenario:**

| Item | Value |
| --- | --- |
| Initial Account A | 1,000 |
| Initial Account B | 0 |
| Concurrent operations | 100 transfers |
| Amount per transfer | 10 |
| Expected Account A | 0 |
| Expected Account B | 1,000 |
| Expected Total | 1,000 |

**Test execution:**

1. Set up Account A with a balance of 1,000.
2. Set up Account B with a balance of 0.
3. Fire 100 concurrent transfers of 10 from A to B.
4. Wait for all operations to complete.
5. Verify the final balances.

**Invariant:**

`Account A + Account B = 1,000`

Total money must always be conserved.

**Verify that the test is meaningful:**

- Temporarily remove the row lock.
- Run the concurrency test.
- Confirm that the test detects the resulting incorrect behavior.
- Re-add the lock.
- Run the test again and confirm it passes.

Keep a note of this experiment in your README.

> "I proved my test catches the bug it's meant to catch."

This is a strong and specific engineering claim.

**Step 5: Error Handling**

Implement centralized error handling using `@ControllerAdvice`.

Handle:

- Insufficient funds.
- Account not found.
- Invalid transaction amount.

**Step 6: Documentation**

Write a README explaining:

- The concurrency problem in plain English.
- Why it occurs.
- How your locking strategy solves it.
- How to run the application and tests.

### Phase 2 — Strong Portfolio Version (Target: +1–2 Weeks)

This phase turns the MVP into a more convincing backend engineering project.

**1\. Idempotency Keys**

Reject duplicate transfer requests using the same idempotency key.

Simulate a client retry after a timeout and confirm that money does not move twice.

This is essential, not optional. It is one of the most common real-world payment-system problems.

**2\. Concurrency Strategy Comparison**

Implement both:

- Pessimistic locking (Phase 1).
- Optimistic locking (using the `version` column).

Load-test both under the same contention scenario.

Record:

- Throughput.
- Failure rate.
- Transaction success rate.
- Performance under contention.

You do not need exhaustive benchmarks. Even a rough comparison gives you real trade-offs to discuss in an interview instead of just textbook answers.

**3\. Authentication**

- Add JWT authentication.
- Implement registration and login.
- Protect API endpoints using Spring Security.

**4\. Structured Logging**

Add structured logging with a correlation/request ID for every API call.

**5\. Continuous Integration**

Wire the complete test suite into GitHub Actions, including the concurrency test.

**6\. Basic Load Testing**

Use k6 or a simple custom script.

Put real numbers in your README.

Example:

> Processed X concurrent transfers/sec with zero balance errors.

Only publish numbers that you have actually measured.

### Phase 3 — Exceptional / Interview-Deep (Optional)

Pick 1–2 features, not all of them.

**Option 1: Partial-Failure Simulation**

- Simulate a process failure between debit and credit.
- Demonstrate how a transaction can become stuck in `PENDING`.
- Build a simple reconciliation job to resolve it.

This is genuinely great interview material.

**Option 2: Ledger-as-Source-of-Truth Experiment**

- Derive `Account.balance` from the Transaction ledger instead of storing it directly as the authoritative value.
- Explore how to keep the projection consistent.
- Consider recomputation and snapshotting.

Only take this on if you can clearly answer:

> How do these two representations never diverge?

Otherwise, it becomes a liability rather than a strength.

**Option 3: Scheduled / Recurring Transfers**

Add a cron-triggered transfer feature.

This is the only extra feature worth considering because it extends the core domain instead of introducing an unrelated module.

**Option 4: Monitoring and Observability**

- Spring Boot Actuator.
- Prometheus.
- A basic Grafana dashboard.

Visualize:

- Transaction throughput.
- Transaction error rate.

---

## 6\. Optional: Minimal Frontend

**Add this after Phase 1. Do not let it delay backend development.**

### Purpose

Turn "trust me, it works" into a live demonstration.

The frontend is not where you demonstrate frontend skills. It is evidence that your backend works.

### Scope: Three Screens Maximum

1. **Create Account**
   - Simple account creation form.
2. **Transfer Money**
   - From account.
   - To account.
   - Amount.
3. **View Balance + Transaction History**
   - Display account balance.
   - Display transaction history in a simple table.

### Technology

- React.
- No state management library.
- No routing library unless you actually need two or more real pages.
- No design system.
- Plain components and forms.

**Time constraint:** If you spend more than approximately 10–15% of the total project time on the frontend, stop.

That time belongs in Phase 2 or Phase 3 of the backend.

### One Feature Actually Worth Building: Stress Test Button

Build a button that:

1. Fires N simultaneous transfer requests against the same account.
2. Displays the balance before the test.
3. Displays the balance after the test.
4. Shows whether the expected balance invariant holds.

This turns the frontend into a second piece of proof for your concurrency guarantee, rather than just a form wrapper around your API.

It is also genuinely fun to demonstrate in an interview.

### Explicitly Skip

- Authentication UI polish.
- Animations.
- Responsive/mobile design.
- Theming.

These are unlikely to come up in a backend interview. Time spent here is time not spent on the reconciliation job or locking benchmark.

---

## 7\. What Your README Should Lead With

Do not lead with a feature list.

Lead with the engineering problem and your proof.

### 1\. The Problem

> Concurrent transfers on the same account can corrupt balances.

### 2\. Your Solution

Explain:

- Which locking strategy you implemented.
- Why you chose it.
- How it prevents concurrent balance corruption.

### 3\. The Proof

Show:

- A screenshot or code snippet of the concurrency test passing.
- Ideally, evidence that the test fails without the locking fix.

### 4\. Measurable Results

Include real performance numbers if available.

Examples:

- Transactions processed per second.
- Number of concurrent requests tested.
- Correctness rate under load.
- Failure rate under contention.

**The README should communicate one thing immediately:**

This project does not just claim to handle concurrent transactions correctly. It demonstrates that correctness through automated tests.

---

## 8\. Guardrails While Building

### Guardrail 1: Avoid Scope Creep

Before adding a feature, ask:

> Does this make the concurrency/consistency story deeper, or is it scope creep?

If it is unrelated (booking, products, social features), do not add it.

### Guardrail 2: Avoid Unnecessary Infrastructure

Before adding infrastructure such as microservices, message queues, or Kubernetes, ask:

> Can I explain in one sentence why I need this, beyond "it's scalable"?

If not, skip it.

### Guardrail 3: Understand Everything You Build

Keep the entire project small enough that you could rebuild and explain it from memory, line by line, in an interview.

---

## Final Project Goal

Build a focused financial transaction backend that demonstrates strong fundamentals in:

- Database transactions and ACID properties.
- Concurrency control and race-condition prevention.
- Pessimistic vs. optimistic locking.
- Idempotency and safe retries.
- Ledger-based auditability.
- Automated integration testing with real PostgreSQL.
- CI/CD and performance measurement.

**The ultimate differentiator is not how many features you build. It is whether you can prove that your system preserves financial correctness under concurrent requests—and explain exactly why it works.**
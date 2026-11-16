# Agoda Full-Stack Internship (2027) Technical Interview Preparation Guide
## Flagship Project: Concurrent-Safe Transaction Engine

This guide provides deep technical talking points, architecture justifications, and exact answers for questions interviewers at Agoda (and top tech companies) frequently ask regarding concurrency, database integrity, distributed patterns, and system design.

---

## 1. Concurrency & Database Locking

### Q1: "Why did you choose Pessimistic Locking over Optimistic Locking for money transfers?"
**Strong Answer:**
> "In financial transactions, money deduction is a high-contention, low-latency critical path. With **Optimistic Locking (`@Version`)**, every transaction reads the version and only validates it at commit time. Under high contention—which we empirically benchmarked with 100 concurrent threads—optimistic locking resulted in a **93% failure rate (only 7% success)** due to optimistic lock conflicts, requiring aggressive retry loops that degrade throughput and consume unnecessary DB connections.
> 
> With **Pessimistic Locking (`SELECT ... FOR UPDATE`)**, the database serializes modifications at the row level. In our identical 100-thread benchmark, pessimistic locking achieved **100% transfer success (100/100 completed)** without a single retry, completed in 521ms, and strictly maintained the money conservation invariant ($1000 initial = $1000 final). In high-contention banking domains, pessimistic locking guarantees deterministic completion without burning CPU and network cycles on cascading rollback retries."

### Q2: "How do you prevent database deadlocks when two accounts transfer money to each other simultaneously?"
**Strong Answer:**
> "Deadlocks occur due to circular wait conditions—e.g., Thread 1 locks Account A and waits for Account B, while Thread 2 locks Account B and waits for Account A.
> 
> We prevent this mathematically by enforcing **deterministic lock ordering**. Regardless of who is sender and receiver, our `TransactionService` compares the two account UUIDs lexicographically before acquiring any locks:
> ```
> FirstLock  = min(senderId, receiverId)
> SecondLock = max(senderId, receiverId)
> ```
> Both Thread 1 and Thread 2 will always acquire the lower UUID first and the higher UUID second. Because locks are always acquired in a strictly monotonic sequence, circular wait is mathematically impossible, eliminating deadlocks entirely."

### Q3: "How does PostgreSQL implement `SELECT ... FOR UPDATE` under the hood?"
**Strong Answer:**
> "PostgreSQL uses Multi-Version Concurrency Control (MVCC). A standard `SELECT` reads a transaction snapshot without blocking writes. However, `SELECT ... FOR UPDATE` acquires an exclusive row-level lock (`XMAX` field in the tuple header is stamped with the locking transaction's ID).
> 
> Any concurrent transaction attempting `SELECT ... FOR UPDATE` or `UPDATE` on the same tuple will pause on a heavyweight row lock wait queue until the locking transaction either `COMMIT`s or `ROLLBACK`s. This ensures no other transaction can read uncommitted state or modify the balance concurrently."

---

## 2. Idempotency & Distributed Systems

### Q4: "What happens if a user clicks 'Transfer' twice or network drops after payment processing? How did you design idempotency?"
**Strong Answer:**
> "We protect against duplicates using client-provided `Idempotency-Key` headers coupled with PostgreSQL **transaction-level advisory locks** (`pg_advisory_xact_lock`):
> 1. When a transfer request arrives, we calculate a 64-bit hash of the idempotency key using `hashtextextended(:key, 0)` and acquire an advisory lock.
> 2. Because it is transaction-scoped, the lock is automatically released upon `COMMIT` or `ROLLBACK` without needing manual cleanup or risk of orphaned distributed locks.
> 3. Inside the lock boundary, we query our `transactions` audit table for an existing transaction with this idempotency key.
> 4. If found with the exact same parameters (sender, receiver, amount), we return the previously committed transaction with **HTTP 200 OK** (safe replay).
> 5. If found with mismatched parameters (same idempotency key, different recipient/amount), we reject it with **HTTP 409 Conflict**.
> 6. If first seen, we execute the transfer, record the transaction row, and return **HTTP 201 Created**."

### Q5: "Why did you use PostgreSQL advisory locks instead of Redis distributed locks (Redlock)?"
**Strong Answer:**
> "For this architecture, PostgreSQL advisory locks provide two massive advantages over Redis:
> 1. **Zero External Infrastructure Dependency:** No additional distributed systems (Redis clusters, ZooKeeper) to operate, monitor, or handle split-brain scenarios.
> 2. **Transactional Lifecycle Bound:** PostgreSQL's `pg_advisory_xact_lock` is strictly tied to the life of the SQL transaction. If the app server crashes or drops connection, PostgreSQL automatically releases the lock upon connection termination. With Redis, you must manage TTLs, clock drift, and lock extensions (keep-alive heartbeats), introducing risk of premature lock expiry while a slow database query is still processing."

---

## 3. Architecture & Spring Boot Best Practices

### Q6: "Why is Account.balance the operational source of truth instead of re-calculating from the Transaction history?"
**Strong Answer:**
> "Calculating balance on-the-fly by summing all historical transactions is an $O(N)$ operation that requires full index/table scans as ledger history grows, making high-frequency transfers unscalable.
> 
> Instead, we treat `Account.balance` as the operational balance with row locking for $O(1)$ low-latency balance checks and updates. Simultaneously, we persist every transfer into the `transactions` table as an immutable audit record within the **exact same database transaction**. If either the balance deduction or the audit insertion fails, the entire transaction rolls back atomically. This gives us both $O(1)$ transactional speed and a 100% complete audit trail."

### Q7: "How is security and authorization enforced across your REST APIs?"
**Strong Answer:**
> "We implemented stateless security using Spring Security 6 and JSON Web Tokens (JWT) with HMAC-SHA256:
> 1. User registration hashes passwords with BCrypt (salt generated automatically).
> 2. On login, the user receives an HS256-signed JWT containing user ID and expiration claims.
> 3. On every protected endpoint, the Spring Security filter extracts the `Jwt` principal.
> 4. In `AccountService` and `TransactionService`, we enforce strict ownership: a user can only view their own accounts, view transaction history of accounts they own, and transfer money *from* accounts they own. Cross-account unauthorized attempts immediately return **HTTP 403 Forbidden**."

### Q8: "How do you achieve distributed tracing and observability in production?"
**Strong Answer:**
> "We implemented a custom `CorrelationIdFilter` placed at `HIGHEST_PRECEDENCE` in the Spring Security filter chain:
> 1. The filter checks for an incoming `X-Request-Id` HTTP header (or generates a random UUID if absent).
> 2. It injects the `requestId` into the SLF4J **Mapped Diagnostic Context (MDC)**.
> 3. Spring Boot's log pattern prints `[%X{requestId}]` on every log line emitted during that HTTP request lifecycle.
> 4. The filter attaches the same `X-Request-Id` to the HTTP response header.
> In a microservice ecosystem like Agoda's, this allows engineers to trace a request across gateway proxies, application servers, and downstream microservices using tools like OpenTelemetry or ELK/Kibana."

---

## 4. Agoda Behavioral & System Scalability Questions

### Q9: "Agoda processes millions of hotel bookings and payments daily. How would you scale this transaction engine for horizontal scale?"
**Strong Answer:**
> "To scale from hundreds to millions of transfers per second:
> 1. **Database Sharding by Account/User ID:** Distribute accounts across multiple PostgreSQL database nodes using consistent hashing on `user_id` or `account_id`.
> 2. **Two-Phase Commit (2PC) or Sagas for Cross-Shard Transfers:** For transfers between accounts on different shards, transition from local DB transactions to an event-driven Saga pattern with compensating transactions orchestrated via Kafka.
> 3. **Read Replicas:** Route read-heavy queries (e.g. paginated transaction history, account details) to read replicas while keeping transactional balance mutations on the primary writer.
> 4. **Caching Layer:** Cache non-volatile user profile data and account metadata in Redis with cache-aside, while leaving balance mutations directly to row-locked PostgreSQL to prevent dirty reads."

### Q10: "Tell me about a technical challenge you encountered in this project and how you resolved it."
**Strong Answer:**
> "A major challenge was preventing deadlocks and race conditions during simultaneous bidirectional transfers. Initially, naive concurrent transfers between two accounts resulted in PostgreSQL deadlock errors (`PSQLException: deadlock detected`).
> 
> I used systematic debugging: reproduced the deadlock with a multi-threaded Testcontainers integration test simulating simultaneous bidirectional transfers. I then engineered a deterministic sorting mechanism using UUID string comparisons so both sender and receiver accounts are consistently locked in lexicographical order. The test passed with zero deadlocks and 100% money conservation, which was further validated with a 50-worker k6/Python load test achieving 134 req/sec with zero balance discrepancies."


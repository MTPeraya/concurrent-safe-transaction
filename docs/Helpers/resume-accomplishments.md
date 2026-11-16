# Resume Bullet Points: Concurrent-Safe Transaction Engine
### Tailored for Agoda Software Engineering Internship (Full-Stack / Backend 2027)

Use these Google XYZ-formatted bullets (`Accomplished [X] as measured by [Y], by doing [Z]`) for your software engineering resume, LinkedIn profile, and internship portfolio.

---

## 🎯 Primary Resume Bullets (Choose 3-4 for your Project section)

### Bullet Option 1: Concurrency & Database Locking
> **Concurrent-Safe Financial Transaction Engine** | *Java 21, Spring Boot 4, PostgreSQL, Docker, JUnit 5*
> - Engineered a high-throughput financial transfer engine guaranteeing **zero balance corruption and 0% deadlock rate** across **100 concurrent threads**, utilizing **PostgreSQL pessimistic row-level locking (`SELECT FOR UPDATE`)** with deterministic UUID sorting order.

### Bullet Option 2: Performance Benchmarking & Reliability
> - Conducted empirical concurrency benchmarks proving pessimistic locking achieved **100% transfer completion rate vs. 7% for JPA optimistic locking (`@Version`)** under high contention, eliminating 93% transaction rollback overheads without balance leakage ($1,000 money conservation invariant verified).

### Bullet Option 3: Distributed Systems & Idempotency
> - Prevented duplicate financial charges and replay attacks by implementing transactional idempotency using **PostgreSQL transaction-scoped advisory locks (`pg_advisory_xact_lock`)** and SHA-hashed request keys, achieving 100% duplicate request suppression without external distributed lock managers.

### Bullet Option 4: Security & Stateless Authentication
> - Architected a stateless security boundary using **Spring Security 6 and JWT (HMAC-SHA256)** with BCrypt password hashing, enforcing fine-grained user ownership authorization and preventing cross-user account tampering across all REST endpoints.

### Bullet Option 5: Observability & Production Readiness
> - Enhanced production observability by developing a servlet `CorrelationIdFilter` with SLF4J MDC integration, injecting unique `X-Request-Id` tracing headers across logs and HTTP responses for distributed request tracing.

### Bullet Option 6: Automated Testing & Load Testing
> - Validated end-to-end data integrity by authoring **45+ automated integration tests** with **Testcontainers** and executing asynchronous load tests achieving **134 requests/sec** with zero failed transactions and zero balance discrepancy.

---

## 🛠 Skills & Keywords to Include in Your Resume Skills Section

- **Languages:** Java 21, TypeScript, SQL (PostgreSQL), Python
- **Backend & Frameworks:** Spring Boot 4, Spring Data JPA, Spring Security, Hibernate, RESTful APIs, JWT
- **Databases & Concurrency:** PostgreSQL 16, Pessimistic Row Locking, Advisory Locks, MVCC, Transaction Isolation (ACID)
- **Frontend:** React, TypeScript, Vite, Tailwind CSS / Modern CSS, Axios
- **DevOps & Testing:** Docker, Docker Compose, Testcontainers, JUnit 5, Git, GitHub Actions CI/CD
- **System Concepts:** Concurrency Control, Deadlock Prevention, Idempotency, Distributed Tracing (MDC), OOP, Clean Architecture


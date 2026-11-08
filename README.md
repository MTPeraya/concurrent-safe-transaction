# Concurrent-Safe Transaction Backend

A production-oriented banking transaction backend built with **Java 21, Spring Boot 4, PostgreSQL, and Docker**.

The project focuses on one important engineering challenge: maintaining financial consistency when multiple money transfers happen concurrently.

It demonstrates database transactions, pessimistic locking, concurrency testing, PostgreSQL integration testing, and paginated transaction history.

---

## 1\. Project Overview

Financial applications must guarantee that concurrent operations never corrupt account balances.

For example, imagine two requests attempting to withdraw money from the same account simultaneously.

Without proper concurrency control, both requests might read the same initial balance and overwrite each other's updates.

This project addresses that problem using PostgreSQL transactions and row-level locking.

The primary engineering goal is:

**Every successful transfer must preserve financial consistency, even under concurrent requests.**

## 2\. Core Engineering Challenge: Concurrent Transfers

The project implements money transfers using:

- Spring's `@Transactional`.
- PostgreSQL row-level locking.
- Pessimistic locking through JPA's `PESSIMISTIC_WRITE`.
- Consistent account locking order to reduce deadlock risks.
- Database rollback when a transfer fails.

### Concurrency strategy

Before updating account balances, the application:

1. Validates the transfer request.
2. Determines a consistent locking order using account UUIDs.
3. Acquires locks on both accounts.
4. Checks whether the sender has sufficient funds.
5. Updates sender and receiver balances.
6. Creates a transaction audit record.
7. Commits everything within one database transaction.

If an operation fails, the database transaction rolls back.

This prevents partial transfers where money is deducted from one account but not credited to another.

## 3\. Concurrency Testing: 100 Simultaneous Transfers

The project includes an automated integration test that submits 100 concurrent transfers against the same two accounts.

### Test scenario

| Item | Initial value |
| --- | --- |
| Alice's balance | $1,000.00 |
| Bob's balance | $0.00 |
| Number of transfers | 100 |
| Amount per transfer | $10.00 |

Expected result:

| Item | Expected value |
| --- | --- |
| Successful transfers | 100 |
| Failed transfers | 0 |
| Alice's balance | $0.00 |
| Bob's balance | $1,000.00 |
| Total balance | $1,000.00 |
| Transaction records | 100 |

### Verified test results

**100 concurrent transfers successfully completed.**

- Successful transfers: 100
- Failed transfers: 0
- Alice's final balance: $0.00
- Bob's final balance: $1,000.00
- Total money preserved: $1,000.00
- Database transaction records: 100

The test verifies the final balances and confirms that the number of persisted transaction records matches the successful transfers.

## 4\. Technology Stack

| Layer | Technology | Purpose |
| --- | --- | --- |
| Language | Java 21 | Application development |
| Framework | Spring Boot 4 | Backend application |
| ORM | Spring Data JPA / Hibernate | Database persistence |
| Database | PostgreSQL | ACID transactions and row-level locking |
| Database migrations/schema | Hibernate DDL | Entity-based schema management |
| Testing | JUnit 5 | Automated testing |
| Integration testing | Testcontainers | Real PostgreSQL test database |
| Mocking | Mockito | Isolated testing and failure simulation |
| Build tool | Maven | Dependency management and builds |
| Containerization | Docker | Application and database environments |

The project deliberately uses a modular monolithic architecture rather than introducing unnecessary microservices.

## 5\. Architecture

The application follows a layered architecture.

text

```text
Client
  |
  v
REST Controllers
  |
  v
Service Layer
  |
  +-------------------+
  |                   |
  v                   v
AccountRepository  TransactionRepository
  |                   |
  +---------+---------+
            |
            v
        PostgreSQL
```

### Main layers

- **Controller:** Handles HTTP requests and responses.
- **Service:** Contains business logic and transaction management.
- **Repository:** Handles database queries and persistence.
- **Entity:** Represents database records.
- **DTO:** Defines API request and response structures.
- **Exception:** Handles application errors centrally.

## 6\. Domain Model

### Account

Represents a customer's bank account.

| Field | Description |
| --- | --- |
| id | UUID primary key |
| ownerName | Account owner's name |
| balance | Current account balance |
| version | Optimistic locking version |
| createdAt | Account creation timestamp |

### Transaction

Represents an immutable transaction audit record.

| Field | Description |
| --- | --- |
| id | UUID primary key |
| fromAccountId | Sender account UUID |
| toAccountId | Receiver account UUID |
| amount | Transfer amount |
| status | Transaction status |
| createdAt | Transaction creation timestamp |

Transaction records are created as part of the same database transaction that updates account balances.

The history feature reads these records without modifying them.

## 7\. Implemented Features

### Account Management

- Account creation.
- Account retrieval.
- Account balance retrieval.

### Money Transfers

- Transfer money between accounts.
- Validate transfer amounts.
- Prevent transfers between the same account.
- Check sufficient funds.
- Handle missing accounts.
- Maintain atomic database transactions.
- Roll back failed transfers.

### Concurrency Control

- PostgreSQL pessimistic row-level locking.
- Consistent account locking order.
- Protection against concurrent balance updates.
- Automated 100-concurrent-transfer integration test.

### Transaction History API

- Retrieve transactions involving an account.
- Include both sent and received transactions.
- Paginated results using Spring Data JPA.
- Sort transactions by creation time.
- Support page numbers and page sizes.
- Validate account existence.
- Return appropriate HTTP errors.

### Centralized Exception Handling

- Account not found.
- Insufficient funds.
- Invalid transfer requests.
- Request validation errors.
- Consistent HTTP error responses.

### PostgreSQL Integration Testing

Integration tests run against an actual PostgreSQL database using Testcontainers.

This avoids relying on H2, whose locking and concurrency behavior can differ from PostgreSQL.

Docker automatically starts the PostgreSQL test container when the tests run.

## 8\. REST API

### Account Endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| POST | `/accounts` | Create an account |
| GET | `/accounts/{id}` | Retrieve account details |

### Transaction Endpoints

| Method | Endpoint | Description |
| --- | --- | --- |
| POST | `/transactions` | Transfer money |
| GET | `/accounts/{id}/transactions` | Retrieve paginated transaction history |

### Transaction History API

**Endpoint**

http

```http
GET /accounts/{accountId}/transactions
```

**Query parameters**

| Parameter | Default | Description |
| --- | --- | --- |
| page | 0 | Zero-based page number |
| size | 10 | Number of records per page (maximum 100) |

**Example request**

http

```http
GET /accounts/123e4567-e89b-12d3-a456-426614174000/transactions?page=0&size=10
```

**Example response**

json

```json
{
  "content": [
    {
      "id": "transaction-uuid",
      "fromAccountId": "sender-uuid",
      "toAccountId": "receiver-uuid",
      "amount": 100.00,
      "status": "COMPLETED",
      "createdAt": "2026-11-08T10:30:00Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 25,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

**Sorting**

Transactions are ordered by:

1. `createdAt DESC`
2. `id DESC`

This provides deterministic ordering when multiple records have the same timestamp.

**Error responses**

| HTTP Status | Description |
| --- | --- |
| 400 Bad Request | Invalid pagination parameters |
| 404 Not Found | Account does not exist |

## 9\. Testing

Testing is an important part of this project, particularly because financial applications require reliable transaction behavior.

### Testing strategy

| Test type | Purpose |
| --- | --- |
| Unit tests | Test individual business logic components |
| Integration tests | Test services, repositories and database behavior |
| Concurrency tests | Verify simultaneous transfers |
| API tests | Verify HTTP endpoints and responses |

### PostgreSQL with Testcontainers

The integration tests use Testcontainers to launch a real PostgreSQL database.

Benefits:

- Tests actual PostgreSQL behavior.
- Verifies database transactions.
- Tests pessimistic locking.
- Avoids differences between H2 and PostgreSQL.
- Provides an isolated database environment for tests.

### Run the tests

Execute the complete test suite:

bash

```bash
./mvnw clean test
```

### Latest verified test results

text

```text
Tests run: 23
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

The test suite includes the 100-concurrent-transfer test and transaction history integration tests.

## 10\. Running Locally

### Prerequisites

- Java 21
- Maven (or the included Maven Wrapper)
- Docker Desktop

### Run the application

Start PostgreSQL and the application using Docker Compose:

bash

```bash
docker compose up --build
```

### Run tests independently

bash

```bash
./mvnw clean test
```

Docker must be running because Testcontainers requires Docker to create the PostgreSQL test database.

## 11\. Development Roadmap

The following features are planned but have not yet been implemented.

- [ ]**Idempotency:** Prevent duplicate transfers using an `Idempotency-Key`, database uniqueness constraints and retry handling.
- [ ]**Optimistic vs. pessimistic locking:** Implement and compare both approaches under concurrent workloads.
- [ ]**Authentication and authorization:** Spring Security, password hashing, registration, login and JWT.
- [ ]**Production logging:** Structured logging and correlation IDs.
- [ ]**Production error handling:** Consistent error responses and additional security improvements.
- [ ]**CI/CD:** GitHub Actions, automated PostgreSQL integration tests and deployment.
- [ ]**Performance testing:** Measure throughput, latency and concurrency behavior.

## 12\. Future Improvements

Additional engineering improvements to explore:

- Partial-failure simulation and reconciliation for transactions stuck in `PENDING`.
- Investigate deriving account balances from the transaction ledger.
- Scheduled and recurring transfers.
- Spring Boot Actuator.
- Prometheus and Grafana monitoring.
- A React frontend for account management, transfers and transaction history.

---

## Project Goal

This project is an opportunity to develop practical backend engineering skills by building a financial application that prioritizes:

- Data consistency.
- Database transaction management.
- Concurrency control.
- Reliable integration testing.
- Clean architecture.
- Production-oriented development practices.

**The goal is to understand and demonstrate why money transfers remain correct under concurrent execution.**

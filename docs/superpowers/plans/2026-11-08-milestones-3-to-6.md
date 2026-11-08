# Milestones 3 Through 6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete production security, logging, concurrency benchmarking, comprehensive portfolio documentation, and minimal React+TypeScript frontend for the Concurrent-Safe Transaction system to prepare for Agoda Cooperative Internship 2027.

**Architecture:** Spring Boot 4 backend with PostgreSQL pessimistic/advisory locking and JWT authentication, complemented by correlation ID logging filter, performance benchmarks, and a lightweight React/TypeScript UI for demonstration.

**Tech Stack:** Java 21, Spring Boot 4, Spring Security, JWT, PostgreSQL, Testcontainers, JUnit 5, React, TypeScript, Vite.

---

## Global Constraints
- Java 21, Spring Boot 4, PostgreSQL 16.
- All secrets dynamically provided (no hardcoded credentials).
- Money conservation must be mathematically verified in all concurrent execution paths.
- All 42 existing tests must continue to pass throughout every milestone.

---

## Tasks

### Task 1: Request Correlation IDs & Structured Logging (Milestone 3)
**Files:**
- Create: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/filter/CorrelationIdFilter.java`
- Modify: `transaction-backend/src/main/java/com/mtperaya/transactionbackend/config/SecurityConfig.java`
- Test: `transaction-backend/src/test/java/com/mtperaya/transactionbackend/filter/CorrelationIdFilterTest.java`

- [ ] **Step 1: Write failing test for `CorrelationIdFilter`**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `CorrelationIdFilter` placing `X-Request-Id` into MDC and response header**
- [ ] **Step 4: Register filter in Spring Security filter chain before `SecurityContextHolderFilter`**
- [ ] **Step 5: Run tests and verify all pass**

---

### Task 2: Locking Benchmarking & Load Testing Verification (Milestone 4)
**Files:**
- Create: `transaction-backend/src/test/java/com/mtperaya/transactionbackend/benchmark/LockingBenchmarkTest.java`
- Create: `scripts/load_test.py`
- Modify: `README.md` (record genuine measured metrics)

- [ ] **Step 1: Write `LockingBenchmarkTest` executing 100 concurrent transfers under pessimistic vs optimistic conditions**
- [ ] **Step 2: Measure real latency, throughput, and conflict rates; verify exact balance conservation**
- [ ] **Step 3: Create executable Python load testing script for the running Docker backend container**
- [ ] **Step 4: Execute load script against `http://localhost:8080` and capture genuine statistics**

---

### Task 3: Comprehensive Documentation & Portfolio README (Milestone 5)
**Files:**
- Modify: `README.md`

- [ ] **Step 1: Structure README covering Concurrency problem, ACID & Row-level Locking, Advisory Locks for Idempotency, JWT Security, Test Strategy, and Benchmark Evidence**
- [ ] **Step 2: Add Mermaid diagrams for Transfer Flow and Advisory Lock Idempotency Lifecycle**
- [ ] **Step 3: Document Docker setup and API endpoints**

---

### Task 4: React & TypeScript Minimal Frontend (Milestone 6)
**Files:**
- Create: `transaction-frontend/` (Vite + React + TypeScript)
- Components: Auth/Register, Account Creation, Transfer Form, History Table, Concurrent Stress-Test Trigger

- [ ] **Step 1: Initialize Vite React TypeScript frontend**
- [ ] **Step 2: Implement API client with token handling**
- [ ] **Step 3: Implement the 3 core views (Account, Transfer, History) + Stress Test component**
- [ ] **Step 4: Verify end-to-end integration with backend Docker container**

---

### Task 5: Agoda Technical Interview Defense & Resume Strategy
**Files:**
- Create: `docs/agoda-interview-prep.md`
- Create: `docs/resume-accomplishments.md`

- [ ] **Step 1: Document in-depth technical Q&A covering Agoda-specific questions**
- [ ] **Step 2: Create verified resume bullets using the XYZ method (Accomplished [X] as measured by [Y] by implementing [Z])**

# Concurrent Balance Service

Java 21 / Spring Boot 4.1.2 implementation of the Concurrent Balance Service challenge.

> **Step 2 — persistence foundation.** Domain models, PostgreSQL schema, Flyway migrations, and repository interfaces are included. Financial commands, idempotency execution, and concurrency guarantees are **not implemented yet**. Do not submit this step as the final challenge solution.

## Prerequisites

- Java 21
- Docker Engine and Docker Compose v2 (for PostgreSQL and integration tests)
- Maven 3.9+ or the included Maven bootstrap script

## Run locally

```bash
cp .env.example .env
# Edit DB_PASSWORD in .env before starting PostgreSQL.
docker compose --env-file .env up -d --wait
set -a; . ./.env; set +a
./mvnw clean verify
./mvnw spring-boot:run
```

Check `GET http://localhost:8080/actuator/health`. PostgreSQL must be available before starting the application. The first launch automatically applies Flyway migrations. Avoid committing `.env`.

By default, Docker publishes PostgreSQL on host port **55432** to avoid collisions with an existing local PostgreSQL on 5432. If you change `DB_PORT` in `.env`, also update the port in `DB_URL` to match. If you already created `.env` from the previous version, update **that file** too: set `DB_PORT=55432` and `DB_URL=jdbc:postgresql://localhost:55432/balance_db`. This port change does not modify the internal PostgreSQL port, which remains 5432.

On Windows, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` in the shell environment before running `mvnw.cmd`. `docker compose --env-file .env up -d --wait` uses the local `.env` file, but Docker Compose does **not** export those values into the application's shell environment.

## Tests

```bash
./mvnw clean verify
./mvnw clean verify -Pintegration
```

Default tests are fast and do not need Docker. `*IT` classes are compiled even when the `integration` profile is off, so test sources must always compile cleanly. `-Pintegration` additionally runs `*IT` integration tests using PostgreSQL through Testcontainers; Docker is required. The integration test checks Flyway schema creation and Hibernate mapping validation, then persists a sample account. Tests are not a substitute for future concurrency and transactional behavior coverage.

## Architecture

```text
ir.challenge.balance
├── account
│   ├── domain/Account.java
│   └── persistence/AccountRepository.java
├── transaction
│   ├── domain/{BalanceTransaction,OperationType,TransactionStatus}.java
│   └── persistence/BalanceTransactionRepository.java
└── shared
    ├── config/TimeConfiguration.java
    └── error/{ApiError,ApplicationException,ErrorCode,...}.java
```

Domain objects currently use JPA annotations directly to avoid duplicating mappings in a small challenge. This is a deliberate trade-off: the domain is not persistence-agnostic, but there are fewer layers to maintain. Business operations will be implemented through a service boundary and repositories, without putting transaction orchestration in controllers.

## Data model and invariants

- `accounts.account_id` is the externally supplied stable identifier (VARCHAR 64).
- `accounts.balance` is a signed 64-bit integer constrained to be nonnegative. Money is represented in the smallest indivisible unit of the system (no floating-point arithmetic).
- `accounts.version` enables optimistic version checking in addition to future explicit database row locking.
- `balance_transactions.transaction_id` is the global primary key. This unique constraint is the database-level basis for idempotency across threads and application instances.
- `operation_type`, accounts, and amount identify the original request. Retries with the same ID but different parameters must be rejected by the upcoming service layer.
- `status` is `PENDING`, `SUCCEEDED`, or `REJECTED`. Terminal business failures can retain a failure code for deterministic retries. Infrastructure errors must roll back the whole database transaction; a partially applied transfer is never committed.
- SQL constraints enforce valid operation kinds, positive transaction amounts, destination rules, and failure-state consistency.
- No sample accounts or transactions are silently seeded at startup. Test fixtures are created only in tests.

### Concurrency design (planned, not yet implemented)

Credit/debit will lock the affected account row (`PESSIMISTIC_WRITE`) in a database transaction. Transfers will lock both account rows in ascending identifier order, then update both balances in a single database transaction to minimize deadlocks and guarantee atomic rollback. Distinct accounts may proceed independently except for ordinary database resource contention. Duplicate `transaction_id` conflicts will be resolved against the persisted request parameters and terminal result, not just caught and ignored.

### Trade-offs

PostgreSQL was chosen for persistence, row-level locks, uniqueness guarantees, and atomic commit/rollback. This requires an external database and increases test setup cost versus an in-memory implementation. Flyway manages DDL; Hibernate is configured with `ddl-auto: validate` so it does not silently alter schema. No Redis, message broker, distributed lock manager, or event sourcing infrastructure has been added.

## Error contract

`GlobalExceptionHandler` owns HTTP error mapping. `ErrorCode` defines status and message keys; translations live in `src/main/resources/i18n/`. `Accept-Language: fa` selects Persian. Internal stack traces are never sent to clients. The domain uses structured `ApplicationException` values rather than embedding user-facing text in application logic.

## Remaining work

1. Implement `BalanceService` and correct credit/debit behavior with positive-amount validation and overflow protection.
2. Implement atomic transfer, row-level lock acquisition order, and deadlock-related tests.
3. Implement transactional idempotency and request mismatch detection.
4. Add multi-threaded integration tests covering duplicate requests, overdrafts, transfer invariants, and failure rollback.
5. Finalize engineering trade-offs and automated CI once all business operations are implemented.

## Step 2 correction

- Fix compilation against Testcontainers PostgreSQL 2.x, whose `PostgreSQLContainer` is not generic.
- Use configurable host-side PostgreSQL port 55432 to avoid clashes with other local services.

## Suggested commit

```text
feat: establish PostgreSQL persistence and account domain model
```

## Step 03 — Credit and debit

`BalanceService` is the application-facing Java interface. There is intentionally no public money-movement REST API yet. Account creation is a fixture/setup responsibility in this stage; production account provisioning is out of scope.

- All amounts are positive integral minor units; `long` is used as required by the challenge. No floating-point calculations are performed.
- Credit uses overflow-safe addition; debit enforces sufficient balance under a PostgreSQL `PESSIMISTIC_WRITE` row lock. Independent accounts do not share an application-wide mutex.
- A database-unique transaction id is reserved before account locking via `INSERT ... ON CONFLICT DO NOTHING`. Concurrent requests with the same id wait for the winner to commit, then replay its committed outcome. Different payloads for the same id produce `IDEMPOTENCY_CONFLICT`.
- Business failures (`ACCOUNT_NOT_FOUND`, `INSUFFICIENT_FUNDS`, `BALANCE_OVERFLOW`) are persisted as `REJECTED` transactions and replays return the original failure even if the balance subsequently changes. Only validated commands reserve ids; malformed requests are rejected without reservation. Infrastructure failures roll back the entire database transaction, leaving them retryable.
- The transactional executor returns an outcome instead of throwing for business failures, so Spring commits the rejection record. The public facade throws the corresponding `ApplicationException` **after** the executor transaction commits. The global error handler and i18n remain shared.
- This phase intentionally implements only credit, debit, and getBalance; atomic transfers are added in the next commit. 
- Domain tests: `./mvnw clean verify`; PostgreSQL behavioral and concurrent integration tests: `./mvnw clean verify -Pintegration` (Docker required).

### Explicit trade-offs

The database is the cross-instance coordination point: it guarantees durable idempotency and row-level concurrency at the cost of additional I/O and lock wait times. There is no timeout/retry policy for deadlocks or connection failures yet. Consistency is prioritised over throughput. The step-03 concurrency tests use synchronized starts rather than sleeps, but do not constitute a benchmark. Transaction ids are globally unique, not per account. `REJECTED` business outcomes are permanent for their ids.


## Step 04 — Atomic transfer

`BalanceService.transfer(sourceAccountId, destinationAccountId, amount, transactionId)` moves positive integral minor units between two existing accounts. The call has no public HTTP endpoint in this stage.

- **Atomicity:** Both account changes and the transaction result are part of one PostgreSQL database transaction. An infrastructure failure rolls it all back; neither side can commit alone. The destination's overflow and the source's funds are checked *before* mutating either entity.
- **Lock ordering:** Account rows are locked in lexicographically ascending account-id order, regardless of transfer direction. No JVM-wide lock is used. This removes opposing transfer lock-order inversions among these service operations; infrastructure-level deadlocks cannot be ruled out universally.
- **Idempotency:** The same global `transactionId` is claimed with a unique constraint before account locking. Concurrent identical requests serialize on the transaction row. Identical retries replay the recorded terminal result; changed amount, direction, destination, or operation returns `IDEMPOTENCY_CONFLICT`.
- **Business rejections:** Insufficient funds, a missing account, and balance overflow are recorded as terminal `REJECTED` outcomes, so a retry does not unexpectedly transfer money later. The facade throws the business exception only after that outcome commits. Invalid input (including same-account transfer) is rejected before claiming the id.
- **Same-account policy:** `transfer(A, A, amount, id)` returns `SAME_ACCOUNT_TRANSFER` and writes nothing, rather than silently succeeding. The decision avoids ambiguous no-op semantics and is validated by tests.
- **Concurrency:** Independent accounts are not serialized by application-wide synchronization. All credit/debit/transfer paths use the same account-row locking discipline. Opposing transfers acquire both locks in the same order.
- **Limits:** This design relies on one PostgreSQL primary for atomic transactions and does not include cross-database transfers, retries on transient SQL exceptions, or public APIs. It favors correctness over throughput under contention.

Run `./mvnw clean verify` for unit tests and `./mvnw clean verify -Pintegration` for PostgreSQL/Testcontainers integration tests. New tests cover transfer persistence, replay and conflicts, insufficient funds, missing accounts, destination overflow with unchanged source, validation, concurrent opposing transfers, and concurrent duplicate requests.

Suggested commit after tests pass:

```text
feat: implement atomic transfers with ordered account locking
```

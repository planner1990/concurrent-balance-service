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

### Concurrency design

Credit and debit acquire a PostgreSQL row lock on the affected account. Transfers lock both accounts in ascending account-id order within one database transaction. A unique transaction-id claim, made before acquiring account locks, coordinates concurrent duplicate requests across application instances. Rejections for valid requests are committed as terminal outcomes; infrastructure failures roll back the complete database transaction.

### Trade-offs

PostgreSQL was chosen for persistence, row-level locks, uniqueness guarantees, and atomic commit/rollback. This requires an external database and increases test setup cost versus an in-memory implementation. Flyway manages DDL; Hibernate is configured with `ddl-auto: validate` so it does not silently alter schema. No Redis, message broker, distributed lock manager, or event sourcing infrastructure has been added.

## Error contract

`GlobalExceptionHandler` owns HTTP error mapping. `ErrorCode` defines status and message keys; translations live in `src/main/resources/i18n/`. `Accept-Language: fa` selects Persian. Internal stack traces are never sent to clients. The domain uses structured `ApplicationException` values rather than embedding user-facing text in application logic.

## Implementation status

Credit, debit, balance lookup, and atomic transfer are implemented behind the required Java interface. PostgreSQL enforces nonnegative balances and unique transaction IDs; the service provides deterministic replay and ordered account-row locking. External REST endpoints and account-provisioning APIs are intentionally out of scope. See each step below for implementation details and limitations.

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

## Step 05 — Concurrency verification and technical review

The `BalanceStressIT` integration suite extends the existing unit, transfer and concurrency tests with the following evidence:

- **1,000 concurrent operations** against four accounts: balanced credits, debits and transfers; the test verifies aggregate conservation (apart from explicit credits/debits), nonnegative balances and one successful transaction record per unique operation.
- **Unrelated-account progress:** one thread holds a database row lock on account A while a separate credit on account B is required to complete before A's lock is released. This asserts there is no accidental JVM-wide account lock.
- **Rejected retry stability:** a previously rejected debit is retried concurrently after funding; each retry must return the original failure and leave the account unchanged.

The tests rely on a real PostgreSQL container and use latches for controlled contention, not sleeps. These are correctness tests, **not throughput benchmarks**. Timing assertions have CI tolerances and may be sensitive to unusually slow/shared runners. Each test creates UUID-scoped accounts and transaction IDs to avoid cross-test collisions.

### Verify locally

```bash
./mvnw clean verify
./mvnw clean verify -Pintegration
```

The GitHub Actions workflow runs both suites with Java 21 and Docker on a Linux runner. Maven's normal test phase does not run `*IT` classes; the `integration` profile enables Maven Failsafe.

### Engineering decisions and known limitations

| Decision | Benefit | Trade-off / limit |
| --- | --- | --- |
| PostgreSQL transactions and pessimistic account locks | Atomic transfers, durable state, consistent overdraft prevention | Contention serializes operations affecting the same account; requires a database |
| Globally unique transaction ID and `ON CONFLICT DO NOTHING` | At-most-once effect for successful operations, including concurrent retries | Transaction ID is permanently reserved for the original payload |
| Permanently stored business rejection | Deterministic retries after funds change | Callers need a new ID to submit a genuinely new attempt |
| Fixed account lock order | Prevents lock-order inversion among service transfers | Other database workloads may still deadlock; transient failures are not retried automatically |
| `long` minor units + `Math.addExact` | Exact calculations within defined range | Requires a documented money unit; cannot represent values larger than signed 64-bit range |
| Single PostgreSQL transaction boundary | No partially committed transfer | Does not address cross-database or external-system distributed transactions |
| JPA entities as domain types | Smaller codebase and fewer mapping layers | Domain is coupled to JPA; a conscious choice for this scoped challenge |

**Failure semantics:** bad inputs are rejected before reserving their transaction IDs. Valid requests with business failures become `REJECTED`, and replays return the same error. An unexpected persistence or infrastructure exception should roll back the account updates and the transaction claim together; such requests may be retried. This is an architectural guarantee of the single-DB transaction, not a claim that all possible external failures have been tested.

**Operational scope:** no retry/backoff for database deadlocks/timeouts, no cross-region failover, no ledger/event history beyond the transaction row, no account administration API, and no observability or performance SLA. Such features would add complexity beyond the exercise's correctness-first scope.

**Suggested commit (after local verification):**

```text
test: strengthen concurrency coverage and add verification workflow
```

# Concurrent Balance Service

A correctness-first implementation of the **Senior Java Developer — Concurrent Balance Service** challenge using **Java 21**, **Spring Boot 4.1.2**, and **PostgreSQL 17**.

The required Java interface supports `credit`, `debit`, `transfer`, and `getBalance`. Financial operations use durable transaction IDs, database row locks, and a single database transaction per command. Public money-movement REST endpoints are intentionally omitted because the challenge only requires the Java interface.

## Quick start

Prerequisites: JDK 21, Docker Engine with Compose v2, and Maven 3.9+ (or the included `mvnw` Maven bootstrap script). Docker is required for PostgreSQL integration tests.

```bash
cp .env.example .env
# Set a strong, local-only DB_PASSWORD in .env before the first database initialization.
docker compose --env-file .env up -d --wait
set -a; . ./.env; set +a
./mvnw clean verify -Pintegration
./mvnw spring-boot:run
```

In another terminal, check `curl http://localhost:8080/actuator/health` (expected `{"status":"UP"}`). The local database is published to **127.0.0.1:55432** by default. If changing `DB_PORT`, also change the port in `DB_URL`. **Docker Compose reads `.env` but does not export it to the shell**; load it in each shell that starts Spring Boot. For Windows, set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` in the shell before `mvnw.cmd`.

The application does not seed accounts. Account setup in the tests uses `Account.open`; account administration endpoints are outside this exercise. Do not commit `.env` or real credentials. Changing `DB_PASSWORD` in an existing `.env` does not reset a previously initialized PostgreSQL user's password.

## Build and verification

```bash
./mvnw clean verify                 # Unit tests, without Docker
./mvnw clean verify -Pintegration   # Unit + PostgreSQL Testcontainers integration tests
```

Tests cover input validation, successful operations, failed debits, overflow, idempotency, concurrent duplicates, opposing transfers, independent-account progress, and **1,000 concurrent mixed operations**. The rollback regression additionally forces an exception **after both in-memory account changes but before commit**, then checks neither balance nor the transaction claim was persisted; repeating the same ID must succeed normally. Unit and integration tests are run automatically by `.github/workflows/verify.yml` on push/PR.

Concurrency test outcomes demonstrate the scenarios tested; they are not a formal proof of absence of all races or a throughput benchmark. Timeout-dependent tests can be sensitive to overloaded CI runners.

## Design at a glance

```text
ir.challenge.balance
├── account
│   ├── domain/Account.java
│   └── persistence/AccountRepository.java
├── balance/application
│   ├── BalanceService.java           # Public Java contract
│   ├── BalanceServiceImpl.java       # Validation and exception translation
│   ├── BalanceOperationExecutor.java # @Transactional command handling
│   ├── TransactionClaimRepository.java
│   └── OperationResult.java
├── transaction
│   ├── domain/{BalanceTransaction,OperationType,TransactionStatus}.java
│   └── persistence/BalanceTransactionRepository.java
└── shared
    ├── config/TimeConfiguration.java
    └── error/{ErrorCode,ApplicationException,GlobalExceptionHandler,...}.java
```

The domain uses JPA annotations directly to avoid extra mapping layers in this scoped challenge. PostgreSQL schema changes are managed exclusively by Flyway (`db/migration/V1__create_accounts_and_transactions.sql`); Hibernate uses `ddl-auto: validate` and `open-in-view: false`.

### Monetary invariants

- Money is an integral number of the smallest supported unit, represented as `long` (no floating point). Input amount must be positive; opening balance can be zero.
- Debit never makes a balance negative. Credit and transfer-to-destination use overflow-safe addition. Database constraints independently require nonnegative balances and positive transaction amounts.
- Every successful transfer deducts exactly the amount credited to its destination, inside one PostgreSQL transaction. A failed transaction cannot commit half a transfer.
- Transaction IDs are **globally unique** across credit, debit, and transfer. A duplicate with a different operation, account, direction, or amount is rejected as `IDEMPOTENCY_CONFLICT`.

### Concurrency, atomicity and idempotency

1. The service validates inputs before any database write.
2. A `balance_transactions` row is claimed with `INSERT ... ON CONFLICT DO NOTHING` under the same database transaction as the account updates. Competing identical requests coordinate using the unique `transaction_id` constraint, including across multiple app instances.
3. Credit/debit acquire a PostgreSQL pessimistic row lock on the account. Transfer locks two account rows in **ascending lexicographical account-ID order**, regardless of direction. Different accounts do not share a JVM-wide mutex.
4. The command checks account existence, funds and overflow **while holding locks**, changes the balances and records a terminal result; PostgreSQL commits all changes atomically.
5. An identical retry reads the committed transaction row and returns the same outcome without changing balances again. The service facade converts a rejected result to `ApplicationException` **after the executor's transaction has committed**.

Valid business failures (`ACCOUNT_NOT_FOUND`, `INSUFFICIENT_FUNDS`, `BALANCE_OVERFLOW`) are stored as terminal `REJECTED` results. Retrying that ID returns the same rejection, **even after funds change**. A new business attempt requires a new transaction ID. Invalid input (including same-account transfer) is rejected before reserving an ID. Unexpected exceptions roll back claim and account updates, allowing the original ID to be retried. `PENDING` is an intermediate state inside an uncommitted transaction, not an intended durable response.

**Same-account policy:** `transfer(A, A, ...)` fails with `SAME_ACCOUNT_TRANSFER` and reserves no transaction ID; it is not treated as a successful no-op.

### API and errors

The mandatory `BalanceService` Java interface is implemented; no public credit/debit/transfer REST API was required or added. `GET /actuator/health` provides an operational health endpoint.

`GlobalExceptionHandler` provides a central HTTP error contract for future HTTP consumers. `ErrorCode` is the stable error identifier, and localized public messages are resolved from `src/main/resources/i18n/messages*.properties` (for example with `Accept-Language: fa`). Unexpected stack traces are logged on the server and are **not** included in the response.

## Failure and consistency semantics

| Case | Outcome | Persisted transaction ID? |
| --- | --- | --- |
| Successful command | All requested balances updated atomically | Yes, `SUCCEEDED` |
| Exact retry | Original success or rejection replayed | Already present |
| Same ID, changed payload | `IDEMPOTENCY_CONFLICT` | Original record unchanged |
| Invalid input / same-account transfer | Validation exception; no account changes | No |
| Valid request, business failure | No account changes; deterministic rejection | Yes, `REJECTED` |
| Unexpected failure before commit | Whole database transaction rolls back | No |

The default PostgreSQL isolation level is `READ COMMITTED`. Account row locks serialize conflicting writes; transfers acquire them in stable order. `getBalance` is a single-row read and therefore observes a committed snapshot, never a partially committed transfer. Multiple separate reads of different accounts are **not** guaranteed to form one consistent multi-account snapshot unless the caller establishes an appropriate transaction/isolation strategy.

## Engineering trade-offs and limitations

| Choice | Benefit | Cost / limitation |
| --- | --- | --- |
| PostgreSQL as coordination boundary | Durable IDs and atomic ACID transactions across app instances | DB dependency, I/O, lock contention |
| Pessimistic account row locks | Predictable overdraft prevention | Hot accounts serialize; throughput decreases under contention |
| Ordered transfer locks | Avoids transfer-to-transfer inverted account lock ordering | Cannot rule out all external DB deadlocks |
| Terminal business rejection replay | Deterministic retries despite later balance changes | Caller needs a fresh ID for a new attempt |
| JPA domain types | Lean implementation for a small challenge | Domain depends on persistence annotations |
| `long` minor units | Exact arithmetic, matches required interface | Bounded range; currency/scale not modeled |

Not implemented: automatic retries/backoff for DB deadlocks/timeouts, distributed transfers, durable general-ledger accounting, account provisioning, public payment endpoints, rate limiting, custom metrics/traces, or cross-region failover. These are out of scope, not production guarantees. No Redis, Kafka, global JVM locks, or unnecessary microservices were added.

## Submission checklist

1. Run `./mvnw clean verify -Pintegration` with Docker and confirm `BUILD SUCCESS`.
2. Confirm `.env` is ignored (`git check-ignore .env`), wrapper is executable (`git ls-files -s mvnw` after commit), and `git diff --check` is clean.
3. Verify the GitHub Actions workflow succeeds and the Git history shows meaningful incremental changes.
4. Explain why row locks are required despite `@Transactional`, how `ON CONFLICT` coordinates duplicates, why rejected outcomes commit, and how ordered locks prevent a common deadlock pattern.

See [`docs/engineering-decisions.md`](docs/engineering-decisions.md) for reviewer-focused reasoning and failure boundaries.

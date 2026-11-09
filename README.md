# Concurrent Balance Service

A Java 21 / Spring Boot coding challenge focused on correctness under concurrency, transaction consistency, and idempotency.

> **Implementation status — Step 1:** Project bootstrap and shared error infrastructure only. Account operations, persistence, concurrency control, idempotency, and their integration tests have **not** been implemented yet.

## Prerequisites

- JDK 21
- Internet access for Maven dependencies on the first build
- Maven 3.9+ **or** `curl` + `unzip` on Unix-based systems / PowerShell on Windows (the bootstrap scripts obtain Maven automatically)

No database or Docker installation is required for Step 1.

## Build and run

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

On Windows:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

The health endpoint is available at `GET http://localhost:8080/actuator/health` and should return `{"status":"UP"}`.

The initial build requires access to Maven Central. The provided bootstrap scripts use an installed Maven binary when available; otherwise they install Maven 3.9.11 under `.mvn/local/` (ignored by Git).

## Structure

```text
src/main/java/ir/challenge/balance/
├── ConcurrentBalanceApplication.java
└── shared/
    ├── config/TimeConfiguration.java
    └── error/
        ├── ApiError.java
        ├── ApplicationException.java
        ├── ErrorCode.java
        ├── FieldViolation.java
        └── GlobalExceptionHandler.java
src/main/resources/
├── application.yml
└── i18n/
    ├── messages.properties
    └── messages_fa.properties
src/test/java/
└── ir/challenge/balance/...
```

Future features will be grouped by business capability (account, transaction) rather than turning `shared` into a catch-all package.

## Error handling contract

The REST boundary uses a single `@RestControllerAdvice` to convert application, validation, malformed-body and unexpected errors into a consistent response. Error identifiers are enum values, user-facing messages resolve from message bundles, and HTTP statuses are defined with error codes.

Illustrative payload (endpoint business errors are not wired up yet):

```json
{
  "code": "CONFLICT",
  "message": "The request conflicts with the current state.",
  "timestamp": "2026-01-01T00:00:00Z",
  "path": "/api/v1/accounts",
  "violations": []
}
```

Messages default to English; send `Accept-Language: fa` for Persian. Internal exception details are logged server-side, not returned to clients. Time is injected through a `Clock` for deterministic testing.

**Scope of the shared handler:** it currently covers exceptions reaching MVC exception handling. When business REST endpoints are introduced, request validation, unknown routes, and unsupported methods will also receive end-to-end HTTP coverage.

## Planned implementation

1. Domain model, database schema, migrations, and repository.
2. Credit and debit operations with monetary constraints.
3. Account-level concurrency and transfer atomicity.
4. Idempotency with persisted transaction identity and request consistency.
5. Concurrency tests, fault tests, technical design notes, and CI.

## Architecture and trade-offs (current state)

- Spring Boot is an intentionally small runtime foundation.
- Java `long` is required by the challenge's service interface. Amount positivity and overflow safety will be enforced within the domain implementation.
- No database is configured in Step 1; selecting PostgreSQL and a locking strategy is deferred until those choices are implemented and tested.
- An actuator health endpoint supports basic runtime verification without introducing a placeholder business API.
- The shared exception contract is prepared before business endpoints, avoiding inconsistent error payloads across future operations.

## Submission readiness

This repository is **not ready for challenge submission at Step 1**. The financial operations and concurrency guarantees required by the challenge remain to be implemented and verified.

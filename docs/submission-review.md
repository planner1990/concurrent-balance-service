# Submission review

This checklist maps the original coding challenge to evidence in this repository. It is not a claim of formal verification or production readiness.

| Challenge expectation | Implementation / test evidence |
| --- | --- |
| Java 21, Spring Boot, standard build | `pom.xml`, `mvnw`, `.github/workflows/verify.yml` |
| Required `BalanceService` contract | `balance/application/BalanceService.java` |
| Credit, debit, get balance | `BalanceServiceImpl`, `BalanceOperationExecutor`, `BalanceOperationIT` |
| Atomic transfer | `BalanceOperationExecutor.transfer`, `TransferOperationIT` |
| No overdrafts / arithmetic overflow | `Account`, `BalanceConcurrencyIT`, `BalanceOperationIT` |
| Concurrent idempotency, all command types | `TransactionClaimRepository`, `BalanceConcurrencyIT`, `TransferConcurrencyIT` |
| Account independence and 1,000 mixed operations | `BalanceStressIT` |
| Rollback after unexpected exception | `TransferOperationIT.transferFailureRollsBackBalancesAndClaim` |
| Same-account policy, validation | `BalanceServiceImpl`, `TransferOperationIT` |
| Trade-offs / deadlock discussion | `README.md`, `docs/engineering-decisions.md` |

## What the implementation guarantees

Financial commands use the database as the consistency boundary. Successful account changes and a transaction claim commit together. Business rejections persist and replay. An unexpected exception before commit rolls back both the balance updates and claim. Transfer account locks are acquired in deterministic order.

## Assumptions and limitations to explain

- The account population is provisioned externally; there is no public account creation API.
- Amounts are positive `long` values in a conceptual minor unit; currency and scale are not modeled.
- Idempotency is global across command types and uses the entire request payload for conflict detection.
- A previously rejected business command remains rejected on retry even if account balances change later.
- Hot accounts serialize database writes. Row lock ordering reduces transfer-to-transfer deadlocks but cannot exclude all database deadlocks.
- Network timeouts, lock timeouts, connection failures, deadlocks, and process crashes can still occur. Infrastructure-level retry policy is deliberately left to the caller; retry the **same transaction ID** for an uncertain outcome.
- This project is a bounded coding exercise, not a complete ledger or payment gateway.

## Before sharing the repository

1. Run `./mvnw clean verify -Pintegration` on a Docker-enabled machine and confirm both Surefire and Failsafe summaries show zero failures.
2. Run `./mvnw spring-boot:run` with the local `.env`, then check `curl http://localhost:8080/actuator/health`.
3. Check `git status`, `git diff --check`, `git check-ignore .env`, and `git ls-files -s mvnw` (expected executable mode `100755`).
4. Verify `.env` and credentials were **never committed** to Git history. Rotate credentials that were disclosed.
5. Push only after tests pass. Confirm GitHub Actions succeeds and that the repository link is accessible to the reviewer.
6. Review the core code yourself and be able to defend the lock ordering, unique claim and rollback semantics without relying on generated documentation.

Unit and integration results from Step 05 were reported as passing (16 + 23). The Step 06 rollback test and Step 07 naming-only changes need a fresh local verification before submission. Do not treat a successful build from an earlier archive as proof of this archive.

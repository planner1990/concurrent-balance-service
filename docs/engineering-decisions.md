# Engineering decisions and review notes

## Why not an in-memory lock map?

A JVM mutex or `ConcurrentHashMap` can guard one process but not multiple service instances or restart persistence. PostgreSQL provides row-level locking, a globally unique transaction claim and atomic commits with a single coordination system. The cost is database latency and contention on hot accounts.

## Why is the idempotency claim in the same transaction?

`INSERT ... ON CONFLICT DO NOTHING` reserves a globally unique ID while holding the relevant database uniqueness coordination until commit. An equivalent concurrent request waits for the winning transaction to finish, then reads and replays the committed result. If the first request rolls back, its claim disappears and a retry can run. The claim and resulting balances cannot commit separately.

## Why return a result for a business failure?

Spring rolls back by default on an unchecked exception from a transactional method. Instead, the transactional executor records `REJECTED` and returns an outcome. The public facade maps it to `ApplicationException` only after the executor completes and commits. As a result a retry of a valid rejected operation cannot unexpectedly become successful after the balance changes.

## Why lock accounts in a global order?

For `transfer(A,B)` and `transfer(B,A)` we lock whichever account ID sorts first, then the second. A cycle caused solely by opposite account lock order among these service commands is therefore avoided. This is not a blanket guarantee against deadlocks involving other SQL statements or external applications.

## What does transfer atomicity mean here?

Source debit, destination credit and transaction outcome share one JDBC/JPA database transaction. On a pre-commit infrastructure exception, all writes and the claim roll back. The integration test `unexpectedFailureBeforeCommitRollsBackBothBalancesAndTransactionClaim` injects that failure, checks both balances remain unchanged and then retries the ID successfully. There is no out-of-process side effect to coordinate.

## Consistency of reads

An individual `getBalance(accountId)` sees a committed PostgreSQL value. Two independent `getBalance` calls do not promise an identical snapshot across different accounts. Use an explicit transaction with appropriate isolation for cross-account snapshots if such a read model is later required.

## Operational limits

Transactions may wait on row locks. Database downtime and deadlocks are not automatically retried, and no latency/availability SLO is promised. `long` is interpreted as minor units; currency is outside the interface. Domain entities are JPA-aware for simplicity. A full bank-grade system would additionally need a ledger, authorization, observability, secret management, audit policies and operational runbooks.

## Review questions

- Explain why `@Transactional` alone does not prevent lost updates under concurrent read-modify-write operations.
- Explain the serialization effect of a unique transaction ID claim and how payload conflict is detected.
- Explain why a rejected debit keeps its original outcome after the balance is topped up.
- Explain the order of account row locks in opposite-direction transfers and its precise deadlock scope.
- Explain what the injected rollback integration test proves, and what it does not prove.

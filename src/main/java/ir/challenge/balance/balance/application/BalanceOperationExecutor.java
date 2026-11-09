package ir.challenge.balance.balance.application;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import ir.challenge.balance.transaction.domain.BalanceTransaction;
import ir.challenge.balance.transaction.domain.OperationType;
import ir.challenge.balance.transaction.domain.TransactionStatus;
import ir.challenge.balance.transaction.persistence.BalanceTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceOperationExecutor {

    private final TransactionClaimRepository transactionClaims;
    private final BalanceTransactionRepository transactions;
    private final AccountRepository accounts;
    private final Clock clock;

    public BalanceOperationExecutor(TransactionClaimRepository transactionClaims,
            BalanceTransactionRepository transactions, AccountRepository accounts, Clock clock) {
        this.transactionClaims = transactionClaims;
        this.transactions = transactions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public OperationResult executeBalanceChange(OperationType type, String accountId, long amount,
            String transactionId) {
        Instant now = clock.instant();
        boolean claimed = transactionClaims.tryClaim(transactionId, type, accountId, null, amount, now);
        BalanceTransaction transaction = transactions.findById(transactionId).orElseThrow();

        if (!matchesBalanceChange(transaction, type, accountId, amount)) {
            return OperationResult.rejected(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (!claimed) {
            return replayResult(transaction);
        }

        Account account = accounts.findByIdForUpdate(accountId).orElse(null);
        if (account == null) {
            transaction.reject(ErrorCode.ACCOUNT_NOT_FOUND.name(), clock.instant());
            return OperationResult.rejected(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        try {
            if (type == OperationType.CREDIT) {
                account.credit(amount, clock);
            } else if (type == OperationType.DEBIT) {
                account.debit(amount, clock);
            } else {
                throw new IllegalArgumentException(type.name());
            }
        } catch (ApplicationException exception) {
            transaction.reject(exception.errorCode().name(), clock.instant());
            return OperationResult.rejected(exception.errorCode());
        }
        transaction.succeed(clock.instant());
        return OperationResult.succeeded();
    }

    @Transactional
    public OperationResult transfer(String sourceAccountId, String destinationAccountId, long amount,
            String transactionId) {
        Instant now = clock.instant();
        boolean claimed = transactionClaims.tryClaim(transactionId, OperationType.TRANSFER,
                sourceAccountId, destinationAccountId, amount, now);
        BalanceTransaction transaction = transactions.findById(transactionId).orElseThrow();

        if (!matchesTransfer(transaction, sourceAccountId, destinationAccountId, amount)) {
            return OperationResult.rejected(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (!claimed) {
            return replayResult(transaction);
        }

        String firstId = sourceAccountId.compareTo(destinationAccountId) < 0
                ? sourceAccountId : destinationAccountId;
        String secondId = sourceAccountId.compareTo(destinationAccountId) < 0
                ? destinationAccountId : sourceAccountId;

        Account firstAccount = accounts.findByIdForUpdate(firstId).orElse(null);
        Account secondAccount = accounts.findByIdForUpdate(secondId).orElse(null);
        if (firstAccount == null || secondAccount == null) {
            transaction.reject(ErrorCode.ACCOUNT_NOT_FOUND.name(), clock.instant());
            return OperationResult.rejected(ErrorCode.ACCOUNT_NOT_FOUND);
        }

        Account source = sourceAccountId.equals(firstId) ? firstAccount : secondAccount;
        Account destination = destinationAccountId.equals(firstId) ? firstAccount : secondAccount;

        if (source.getBalance() < amount) {
            transaction.reject(ErrorCode.INSUFFICIENT_FUNDS.name(), clock.instant());
            return OperationResult.rejected(ErrorCode.INSUFFICIENT_FUNDS);
        }
        try {
            Math.addExact(destination.getBalance(), amount);
        } catch (ArithmeticException exception) {
            transaction.reject(ErrorCode.BALANCE_OVERFLOW.name(), clock.instant());
            return OperationResult.rejected(ErrorCode.BALANCE_OVERFLOW);
        }

        source.debit(amount, clock);
        destination.credit(amount, clock);
        transaction.succeed(clock.instant());
        return OperationResult.succeeded();
    }

    private boolean matchesTransfer(BalanceTransaction transaction, String sourceAccountId,
            String destinationAccountId, long amount) {
        return transaction.getOperationType() == OperationType.TRANSFER
                && transaction.getSourceAccountId().equals(sourceAccountId)
                && destinationAccountId.equals(transaction.getDestinationAccountId())
                && transaction.getAmount() == amount;
    }

    private boolean matchesBalanceChange(BalanceTransaction transaction, OperationType type,
            String accountId, long amount) {
        return transaction.getOperationType() == type
                && transaction.getSourceAccountId().equals(accountId)
                && transaction.getDestinationAccountId() == null
                && transaction.getAmount() == amount;
    }

    private OperationResult replayResult(BalanceTransaction transaction) {
        if (transaction.getStatus() == TransactionStatus.SUCCEEDED) {
            return OperationResult.succeeded();
        }
        if (transaction.getStatus() == TransactionStatus.REJECTED) {
            try {
                return OperationResult.rejected(ErrorCode.valueOf(transaction.getFailureCode()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(exception);
            }
        }
        throw new IllegalStateException(TransactionStatus.PENDING.name());
    }
}

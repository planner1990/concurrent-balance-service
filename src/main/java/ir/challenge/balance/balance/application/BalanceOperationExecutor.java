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

    private final TransactionClaimRepository claims;
    private final BalanceTransactionRepository transactions;
    private final AccountRepository accounts;
    private final Clock clock;

    public BalanceOperationExecutor(TransactionClaimRepository claims,
            BalanceTransactionRepository transactions, AccountRepository accounts, Clock clock) {
        this.claims = claims;
        this.transactions = transactions;
        this.accounts = accounts;
        this.clock = clock;
    }

    @Transactional
    public OperationResult execute(OperationType type, String accountId, long amount, String transactionId) {
        Instant now = clock.instant();
        boolean claimed = claims.claim(transactionId, type, accountId, amount, now);
        BalanceTransaction transaction = transactions.findById(transactionId).orElseThrow();

        if (!sameRequest(transaction, type, accountId, amount)) {
            return OperationResult.rejected(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (!claimed) {
            return replay(transaction);
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

    private boolean sameRequest(BalanceTransaction transaction, OperationType type, String accountId, long amount) {
        return transaction.getOperationType() == type
                && transaction.getSourceAccountId().equals(accountId)
                && transaction.getDestinationAccountId() == null
                && transaction.getAmount() == amount;
    }

    private OperationResult replay(BalanceTransaction transaction) {
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

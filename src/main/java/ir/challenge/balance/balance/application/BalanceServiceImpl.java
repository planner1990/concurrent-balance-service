package ir.challenge.balance.balance.application;

import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import ir.challenge.balance.transaction.domain.OperationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BalanceServiceImpl implements BalanceService {

    private final AccountRepository accounts;
    private final BalanceOperationExecutor executor;

    public BalanceServiceImpl(AccountRepository accounts, BalanceOperationExecutor executor) {
        this.accounts = accounts;
        this.executor = executor;
    }

    @Override
    public void credit(String accountId, long amount, String transactionId) {
        execute(OperationType.CREDIT, accountId, amount, transactionId);
    }

    @Override
    public void debit(String accountId, long amount, String transactionId) {
        execute(OperationType.DEBIT, accountId, amount, transactionId);
    }

    @Override
    @Transactional(readOnly = true)
    public long getBalance(String accountId) {
        validateAccountId(accountId);
        return accounts.findById(accountId)
                .orElseThrow(() -> new ApplicationException(ErrorCode.ACCOUNT_NOT_FOUND))
                .getBalance();
    }

    private void execute(OperationType type, String accountId, long amount, String transactionId) {
        validateAccountId(accountId);
        if (amount <= 0) {
            throw new ApplicationException(ErrorCode.INVALID_AMOUNT);
        }
        if (transactionId == null || transactionId.isBlank() || transactionId.length() > 100) {
            throw new ApplicationException(ErrorCode.INVALID_TRANSACTION_ID);
        }
        OperationResult result = executor.execute(type, accountId, amount, transactionId);
        if (result.error() != null) {
            throw new ApplicationException(result.error());
        }
    }

    private void validateAccountId(String accountId) {
        if (accountId == null || accountId.isBlank() || accountId.length() > 64) {
            throw new ApplicationException(ErrorCode.INVALID_ACCOUNT_ID);
        }
    }
}

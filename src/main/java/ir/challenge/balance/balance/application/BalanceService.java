package ir.challenge.balance.balance.application;

public interface BalanceService {

    void credit(String accountId, long amount, String transactionId);

    void debit(String accountId, long amount, String transactionId);

    long getBalance(String accountId);
}

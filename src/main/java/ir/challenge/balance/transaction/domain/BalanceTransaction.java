package ir.challenge.balance.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "balance_transactions")
public class BalanceTransaction {

    @Id
    @Column(name = "transaction_id", length = 100, nullable = false, updatable = false)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", length = 16, nullable = false, updatable = false)
    private OperationType operationType;

    @Column(name = "source_account_id", length = 64, nullable = false, updatable = false)
    private String sourceAccountId;

    @Column(name = "destination_account_id", length = 64, updatable = false)
    private String destinationAccountId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private TransactionStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BalanceTransaction() {
    }

    public String getTransactionId() {
        return transactionId;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

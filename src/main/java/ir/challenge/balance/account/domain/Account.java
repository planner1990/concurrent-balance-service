package ir.challenge.balance.account.domain;

import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id", nullable = false, length = 64, updatable = false)
    private String id;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    private Account(String id, long openingBalance, Instant now) {
        this.id = id;
        this.balance = openingBalance;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Account open(String accountId, long openingBalance, Clock clock) {
        if (accountId == null || accountId.isBlank() || accountId.length() > 64) {
            throw new ApplicationException(ErrorCode.INVALID_ACCOUNT_ID);
        }
        if (openingBalance < 0) {
            throw new ApplicationException(ErrorCode.INVALID_AMOUNT);
        }
        return new Account(accountId, openingBalance, Instant.now(Objects.requireNonNull(clock)));
    }

    public String getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package ir.challenge.balance.balance.application;

import ir.challenge.balance.transaction.domain.OperationType;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionClaimRepository {

    private static final String CLAIM_SQL = """
            INSERT INTO balance_transactions
                (transaction_id, operation_type, source_account_id, destination_account_id,
                 amount, status, failure_code, created_at, updated_at)
            VALUES (?, ?, ?, NULL, ?, 'PENDING', NULL, ?, ?)
            ON CONFLICT (transaction_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public TransactionClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean claim(String transactionId, OperationType type, String accountId, long amount, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        return jdbcTemplate.update(CLAIM_SQL, transactionId, type.name(), accountId, amount,
                timestamp, timestamp) == 1;
    }
}

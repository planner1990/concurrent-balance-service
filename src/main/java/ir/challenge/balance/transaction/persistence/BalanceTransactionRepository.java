package ir.challenge.balance.transaction.persistence;

import ir.challenge.balance.transaction.domain.BalanceTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, String> {
}

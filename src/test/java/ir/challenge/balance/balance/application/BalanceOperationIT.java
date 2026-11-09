package ir.challenge.balance.balance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import ir.challenge.balance.transaction.domain.TransactionStatus;
import ir.challenge.balance.transaction.persistence.BalanceTransactionRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class BalanceOperationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("balance_test").withUsername("balance_test").withPassword("balance_test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired BalanceService service;
    @Autowired AccountRepository accounts;
    @Autowired BalanceTransactionRepository transactions;
    @Autowired Clock clock;

    private String accountId;

    @BeforeEach
    void createAccount() {
        accountId = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(accountId, 1000, clock));
    }

    @Test
    void creditAndDebitPersistExpectedBalances() {
        service.credit(accountId, 500, "TX-" + UUID.randomUUID());
        service.debit(accountId, 300, "TX-" + UUID.randomUUID());
        assertThat(service.getBalance(accountId)).isEqualTo(1200);
    }

    @Test
    void repeatedCreditIsAppliedOnce() {
        String id = "TX-" + UUID.randomUUID();
        service.credit(accountId, 500, id);
        service.credit(accountId, 500, id);
        assertThat(service.getBalance(accountId)).isEqualTo(1500);
        assertThat(transactions.findById(id).map(transaction -> transaction.getStatus()))
                .contains(TransactionStatus.SUCCEEDED);
    }

    @Test
    void repeatedDebitIsAppliedOnce() {
        String id = "TX-" + UUID.randomUUID();
        service.debit(accountId, 600, id);
        service.debit(accountId, 600, id);
        assertThat(service.getBalance(accountId)).isEqualTo(400);
    }

    @Test
    void conflictingPayloadCannotReuseId() {
        String id = "TX-" + UUID.randomUUID();
        service.credit(accountId, 100, id);
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT, () -> service.credit(accountId, 200, id));
        assertThat(service.getBalance(accountId)).isEqualTo(1100);
    }

    @Test
    void rejectedDebitIsStableOnRetryAfterCredit() {
        String id = "TX-" + UUID.randomUUID();
        assertError(ErrorCode.INSUFFICIENT_FUNDS, () -> service.debit(accountId, 1500, id));
        service.credit(accountId, 1000, "TX-" + UUID.randomUUID());
        assertError(ErrorCode.INSUFFICIENT_FUNDS, () -> service.debit(accountId, 1500, id));
        assertThat(service.getBalance(accountId)).isEqualTo(2000);
        assertThat(transactions.findById(id).map(transaction -> transaction.getStatus()))
                .contains(TransactionStatus.REJECTED);
    }

    @Test
    void overflowRejectsWithoutChangingBalance() {
        String id = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(id, Long.MAX_VALUE, clock));
        assertError(ErrorCode.BALANCE_OVERFLOW, () -> service.credit(id, 1, "TX-" + UUID.randomUUID()));
        assertThat(service.getBalance(id)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void missingAccountReturnsNotFound() {
        assertError(ErrorCode.ACCOUNT_NOT_FOUND,
                () -> service.debit("missing-" + UUID.randomUUID(), 1, "TX-" + UUID.randomUUID()));
    }

    private void assertError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(code);
    }
}

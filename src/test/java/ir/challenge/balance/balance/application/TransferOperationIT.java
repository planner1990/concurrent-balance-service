package ir.challenge.balance.balance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import ir.challenge.balance.transaction.domain.OperationType;
import ir.challenge.balance.transaction.domain.TransactionStatus;
import ir.challenge.balance.transaction.persistence.BalanceTransactionRepository;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class TransferOperationIT {

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
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired BalanceOperationExecutor executor;

    private String source;
    private String destination;

    @BeforeEach
    void setUpAccounts() {
        source = UUID.randomUUID().toString();
        destination = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(source, 1000, clock));
        accounts.saveAndFlush(Account.open(destination, 500, clock));
    }

    @Test
    void transferFailureRollsBackBalancesAndClaim() {
        String transactionId = transactionId();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            OperationResult result = executor.transfer(source, destination, 300, transactionId);
            assertThat(result.error()).isNull();
            throw new SimulatedInfrastructureFailure();
        })).isInstanceOf(SimulatedInfrastructureFailure.class);

        assertBalances(1000, 500);
        assertThat(transactions.findById(transactionId)).isEmpty();

        service.transfer(source, destination, 300, transactionId);
        assertBalances(700, 800);
        assertThat(transactions.findById(transactionId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.SUCCEEDED);
    }

    private static final class SimulatedInfrastructureFailure extends RuntimeException {
    }

    @Test
    void transferUpdatesBothAccountsInOneTransaction() {
        String transactionId = transactionId();
        service.transfer(source, destination, 300, transactionId);
        assertBalances(700, 800);
        var transaction = transactions.findById(transactionId).orElseThrow();
        assertThat(transaction.getOperationType()).isEqualTo(OperationType.TRANSFER);
        assertThat(transaction.getSourceAccountId()).isEqualTo(source);
        assertThat(transaction.getDestinationAccountId()).isEqualTo(destination);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.SUCCEEDED);
    }

    @Test
    void retryDoesNotMoveMoneyAgain() {
        String transactionId = transactionId();
        service.transfer(source, destination, 300, transactionId);
        service.transfer(source, destination, 300, transactionId);
        assertBalances(700, 800);
    }

    @Test
    void reusedIdWithDifferentRequestIsRejected() {
        String transactionId = transactionId();
        service.transfer(source, destination, 300, transactionId);
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.transfer(source, destination, 200, transactionId));
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.transfer(destination, source, 300, transactionId));
        assertError(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.credit(source, 300, transactionId));
        assertBalances(700, 800);
    }

    @Test
    void insufficientFundsRemainRejectedAfterBalanceChanges() {
        String transactionId = transactionId();
        assertError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> service.transfer(source, destination, 1200, transactionId));
        service.credit(source, 500, transactionId());
        assertError(ErrorCode.INSUFFICIENT_FUNDS,
                () -> service.transfer(source, destination, 1200, transactionId));
        assertBalances(1500, 500);
        assertThat(transactions.findById(transactionId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    void overflowNeverDebitsSource() {
        String fullAccount = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(fullAccount, Long.MAX_VALUE, clock));
        String transactionId = transactionId();
        assertError(ErrorCode.BALANCE_OVERFLOW,
                () -> service.transfer(source, fullAccount, 1, transactionId));
        assertThat(service.getBalance(source)).isEqualTo(1000);
        assertThat(service.getBalance(fullAccount)).isEqualTo(Long.MAX_VALUE);
        assertThat(transactions.findById(transactionId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    void missingTransferAccountPreservesBalances() {
        assertError(ErrorCode.ACCOUNT_NOT_FOUND,
                () -> service.transfer(source, UUID.randomUUID().toString(), 50, transactionId()));
        assertError(ErrorCode.ACCOUNT_NOT_FOUND,
                () -> service.transfer(UUID.randomUUID().toString(), destination, 50, transactionId()));
        assertBalances(1000, 500);
    }

    @Test
    void sameAccountTransferDoesNotClaimId() {
        String transactionId = transactionId();
        assertError(ErrorCode.SAME_ACCOUNT_TRANSFER,
                () -> service.transfer(source, source, 100, transactionId));
        assertThat(transactions.findById(transactionId)).isEmpty();
        assertBalances(1000, 500);
    }

    @Test
    void invalidTransferDoesNotClaimTransactionId() {
        String transactionId = transactionId();
        assertError(ErrorCode.INVALID_AMOUNT,
                () -> service.transfer(source, destination, 0, transactionId));
        assertError(ErrorCode.INVALID_ACCOUNT_ID,
                () -> service.transfer(source, " ", 100, transactionId));
        assertThat(transactions.findById(transactionId)).isEmpty();
    }

    private void assertBalances(long expectedSource, long expectedDestination) {
        assertThat(service.getBalance(source)).isEqualTo(expectedSource);
        assertThat(service.getBalance(destination)).isEqualTo(expectedDestination);
    }

    private String transactionId() {
        return "TX-" + UUID.randomUUID();
    }

    private void assertError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(code);
    }
}

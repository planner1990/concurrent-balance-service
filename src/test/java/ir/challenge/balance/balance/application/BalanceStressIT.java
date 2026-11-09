package ir.challenge.balance.balance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import ir.challenge.balance.transaction.domain.TransactionStatus;
import ir.challenge.balance.transaction.persistence.BalanceTransactionRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class BalanceStressIT {

    private static final int WORKERS = 8;
    private static final int ACCOUNTS = 4;
    private static final int OPERATIONS = 1_000;
    private static final int START_TIMEOUT_SECONDS = 15;
    private static final int COMPLETION_TIMEOUT_SECONDS = 120;
    private static final long INITIAL_BALANCE = 100_000L;
    private static final long DEBIT_AMOUNT = 7L;
    private static final long CREDIT_AMOUNT = 11L;
    private static final long TRANSFER_AMOUNT = 3L;
    private static final String TX_PREFIX = "TX-";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("balance_test").withUsername("balance_test").withPassword("balance_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired BalanceService service;
    @Autowired AccountRepository accounts;
    @Autowired BalanceTransactionRepository transactions;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired Clock clock;

    @Test
    void thousandConcurrentMixedOperationsPreserveAllBalances() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int accountIndex = 0; accountIndex < ACCOUNTS; accountIndex++) {
            String id = UUID.randomUUID().toString();
            ids.add(id);
            accounts.saveAndFlush(Account.open(id, INITIAL_BALANCE, clock));
        }
        String runId = UUID.randomUUID().toString();
        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < OPERATIONS; index++) {
                final int operation = index;
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    int accountIndex = (operation / 4) % ACCOUNTS;
                    String current = ids.get(accountIndex);
                    String transactionId = TX_PREFIX + runId + "-" + operation;
                    switch (operation % 4) {
                        case 0 -> service.credit(current, CREDIT_AMOUNT, transactionId);
                        case 1 -> service.debit(current, DEBIT_AMOUNT, transactionId);
                        case 2 -> service.transfer(current, ids.get((accountIndex + 1) % ACCOUNTS),
                                TRANSFER_AMOUNT, transactionId);
                        default -> service.transfer(current, ids.get((accountIndex + ACCOUNTS - 1) % ACCOUNTS),
                                TRANSFER_AMOUNT, transactionId);
                    }
                    return null;
                }));
            }
            assertThat(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
        long expectedSum = INITIAL_BALANCE * ACCOUNTS
                + (OPERATIONS / 4L) * (CREDIT_AMOUNT - DEBIT_AMOUNT);
        long actualSum = ids.stream().mapToLong(service::getBalance).sum();
        assertThat(actualSum).isEqualTo(expectedSum);
        for (String id : ids) {
            assertThat(service.getBalance(id)).isGreaterThanOrEqualTo(0L);
        }
        assertThat(transactions.findAllById(transactionIds(runId)).stream()
                .filter(transaction -> transaction.getStatus() == TransactionStatus.SUCCEEDED))
                .hasSize(OPERATIONS);
    }

    @Test
    void independentAccountIsNotBlockedByAnotherAccountsRowLock() throws Exception {
        String locked = UUID.randomUUID().toString();
        String independent = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(locked, 100, clock));
        accounts.saveAndFlush(Account.open(independent, 100, clock));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                accounts.findByIdForUpdate(locked).orElseThrow();
                lockHeld.countDown();
                try {
                    if (!releaseLock.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Account lock was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(lockHeld.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                Future<?> other = pool.submit(() -> service.credit(independent, 25,
                        TX_PREFIX + UUID.randomUUID()));
                other.get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(service.getBalance(independent)).isEqualTo(125);
            } finally {
                releaseLock.countDown();
                holder.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void concurrentRetriesOfRejectedDebitRemainRejectedAfterFunding() throws Exception {
        String id = UUID.randomUUID().toString();
        String transactionId = TX_PREFIX + UUID.randomUUID();
        accounts.saveAndFlush(Account.open(id, 50, clock));
        assertRejected(() -> service.debit(id, 80, transactionId), ErrorCode.INSUFFICIENT_FUNDS);
        service.credit(id, 100, TX_PREFIX + UUID.randomUUID());
        try (ExecutorService pool = Executors.newFixedThreadPool(WORKERS)) {
            CountDownLatch ready = new CountDownLatch(WORKERS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < WORKERS; index++) {
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    assertRejected(() -> service.debit(id, 80, transactionId), ErrorCode.INSUFFICIENT_FUNDS);
                    return null;
                }));
            }
            assertThat(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }
        assertThat(service.getBalance(id)).isEqualTo(150);
        assertThat(transactions.findById(transactionId).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.REJECTED);
    }

    private static List<String> transactionIds(String runId) {
        List<String> ids = new ArrayList<>(OPERATIONS);
        for (int index = 0; index < OPERATIONS; index++) {
            ids.add(TX_PREFIX + runId + "-" + index);
        }
        return ids;
    }

    private static void assertRejected(Runnable operation, ErrorCode expected) {
        try {
            operation.run();
            throw new AssertionError("Expected operation rejection");
        } catch (ApplicationException exception) {
            assertThat(exception.errorCode()).isEqualTo(expected);
        }
    }
}

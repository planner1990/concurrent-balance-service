package ir.challenge.balance.balance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
class BalanceConcurrencyIT {

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
    @Autowired Clock clock;

    @Test
    void simultaneousDebitsCannotOverdraw() throws Exception {
        String accountId = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(accountId, 1000, clock));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.debit(accountId, 700, "TX-" + UUID.randomUUID());
                    return true;
                } catch (ApplicationException ex) {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    return false;
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int success = 0;
            for (Future<Boolean> task : tasks) {
                if (task.get(20, TimeUnit.SECONDS)) success++;
            }
            assertThat(success).isEqualTo(1);
            assertThat(service.getBalance(accountId)).isEqualTo(300);
        }
    }

    @Test
    void duplicateConcurrentCreditsOnlyApplyOnce() throws Exception {
        String accountId = UUID.randomUUID().toString();
        String txId = "TX-" + UUID.randomUUID();
        accounts.saveAndFlush(Account.open(accountId, 0, clock));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                start.await();
                service.credit(accountId, 100, txId);
                return true;
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Boolean> task : tasks) assertThat(task.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(service.getBalance(accountId)).isEqualTo(100);
        }
    }
}

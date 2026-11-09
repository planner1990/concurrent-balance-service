package ir.challenge.balance.balance.application;

import static org.assertj.core.api.Assertions.assertThat;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
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
class TransferConcurrencyIT {

    private static final int REQUESTS = 40;
    private static final int TIMEOUT_SECONDS = 40;

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
    void opposingTransfersCompleteWithoutDeadlockAndPreserveSum() throws Exception {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        accounts.saveAndFlush(Account.open(first, 1000, clock));
        accounts.saveAndFlush(Account.open(second, 1000, clock));

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = IntStream.range(0, REQUESTS).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                start.await();
                if ((index & 1) == 0) {
                    service.transfer(first, second, 10, "TX-" + UUID.randomUUID());
                } else {
                    service.transfer(second, first, 10, "TX-" + UUID.randomUUID());
                }
                return true;
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Boolean> task : tasks) {
                assertThat(task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(service.getBalance(first)).isEqualTo(1000);
        assertThat(service.getBalance(second)).isEqualTo(1000);
    }

    @Test
    void simultaneousRetryOfTransferAppliesOnlyOnce() throws Exception {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String transactionId = "TX-" + UUID.randomUUID();
        accounts.saveAndFlush(Account.open(first, 1000, clock));
        accounts.saveAndFlush(Account.open(second, 0, clock));

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var tasks = IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                start.await();
                service.transfer(first, second, 250, transactionId);
                return true;
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Boolean> task : tasks) {
                assertThat(task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(service.getBalance(first)).isEqualTo(750);
        assertThat(service.getBalance(second)).isEqualTo(250);
    }
}

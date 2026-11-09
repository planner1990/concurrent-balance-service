package ir.challenge.balance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import ir.challenge.balance.account.domain.Account;
import ir.challenge.balance.account.persistence.AccountRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class PersistenceIT {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine")
                    .withDatabaseName("balance_test")
                    .withUsername("balance_test")
                    .withPassword("balance_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void flywayCreatesSchemaAndJpaCanPersistAccount() {
        Account account = Account.open("ACC-IT-001", 1200, Clock.system(ZoneOffset.UTC));
        accountRepository.saveAndFlush(account);

        assertThat(accountRepository.findById(account.getId()))
                .isPresent()
                .get()
                .extracting(Account::getBalance)
                .isEqualTo(1200L);
    }
}

package ir.challenge.balance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConcurrentBalanceApplicationTests {

    @Test
    void applicationEntryPointIsDefined() {
        assertThat(ConcurrentBalanceApplication.class).isNotNull();
    }
}

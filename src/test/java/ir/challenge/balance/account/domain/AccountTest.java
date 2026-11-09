package ir.challenge.balance.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String ACCOUNT_ID = "ACC-001";

    @Test
    void opensAccountWithZeroBalance() {
        Account account = Account.open(ACCOUNT_ID, 0, CLOCK);

        assertThat(account.getId()).isEqualTo(ACCOUNT_ID);
        assertThat(account.getBalance()).isZero();
        assertThat(account.getCreatedAt()).isEqualTo(CLOCK.instant());
        assertThat(account.getUpdatedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void creditsWithoutPrecisionLoss() {
        Account account = Account.open(ACCOUNT_ID, 1000, CLOCK);
        account.credit(500, CLOCK);
        assertThat(account.getBalance()).isEqualTo(1500);
    }

    @Test
    void debitCannotOverdraw() {
        Account account = Account.open(ACCOUNT_ID, 1000, CLOCK);
        assertThatThrownBy(() -> account.debit(1001, CLOCK))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(account.getBalance()).isEqualTo(1000);
    }

    @Test
    void creditCannotOverflow() {
        Account account = Account.open(ACCOUNT_ID, Long.MAX_VALUE, CLOCK);
        assertThatThrownBy(() -> account.credit(1, CLOCK))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.BALANCE_OVERFLOW);
        assertThat(account.getBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsNegativeOpeningBalance() {
        assertThatThrownBy(() -> Account.open(ACCOUNT_ID, -1, CLOCK))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_AMOUNT);
    }

    @Test
    void rejectsBlankIdentifier() {
        assertThatThrownBy(() -> Account.open(" ", 0, CLOCK))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ACCOUNT_ID);
    }

    @Test
    void rejectsTooLongIdentifier() {
        assertThatThrownBy(() -> Account.open("A".repeat(65), 0, CLOCK))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ACCOUNT_ID);
    }
}

package ir.challenge.balance.balance.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;

import ir.challenge.balance.account.persistence.AccountRepository;
import ir.challenge.balance.shared.error.ApplicationException;
import ir.challenge.balance.shared.error.ErrorCode;
import org.junit.jupiter.api.Test;

class BalanceServiceImplTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BalanceOperationExecutor executor = mock(BalanceOperationExecutor.class);
    private final BalanceServiceImpl service = new BalanceServiceImpl(accounts, executor);

    @Test
    void rejectsInvalidAmountBeforePersistence() {
        assertThatThrownBy(() -> service.credit("A", 0, "TX-1"))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_AMOUNT);
        verifyNoInteractions(executor);
    }

    @Test
    void rejectsInvalidTransactionIdBeforePersistence() {
        assertThatThrownBy(() -> service.debit("A", 100, " "))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TRANSACTION_ID);
        verifyNoInteractions(executor);
    }

    @Test
    void rejectsInvalidAccountIdBeforePersistence() {
        assertThatThrownBy(() -> service.credit(" ", 100, "TX-1"))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_ACCOUNT_ID);
        verifyNoInteractions(executor);
    }
    @Test
    void rejectsSameAccountTransferBeforePersistence() {
        assertThatThrownBy(() -> service.transfer("A", "A", 100, "TX-1"))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);
        verifyNoInteractions(executor);
    }

    @Test
    void rejectsInvalidTransferAmountBeforePersistence() {
        assertThatThrownBy(() -> service.transfer("A", "B", 0, "TX-1"))
                .isInstanceOf(ApplicationException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_AMOUNT);
        verifyNoInteractions(executor);
    }

}

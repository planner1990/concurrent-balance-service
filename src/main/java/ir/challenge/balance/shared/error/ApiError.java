package ir.challenge.balance.shared.error;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String path,
        List<FieldViolation> violations
) {
    public ApiError {
        violations = List.copyOf(violations);
    }
}

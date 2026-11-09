package ir.challenge.balance.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;
    private final Clock clock;

    public GlobalExceptionHandler(MessageSource messageSource, Clock clock) {
        this.messageSource = messageSource;
        this.clock = clock;
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiError> handleApplicationException(
            ApplicationException exception, HttpServletRequest request
    ) {
        return response(exception.errorCode(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(
            MethodArgumentNotValidException exception, HttpServletRequest request
    ) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .distinct()
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return response(ErrorCode.VALIDATION_FAILED, request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request
    ) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .distinct()
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return response(ErrorCode.VALIDATION_FAILED, request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableRequest(
            HttpMessageNotReadableException exception, HttpServletRequest request
    ) {
        return response(ErrorCode.INVALID_REQUEST, request, List.of());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleMissingEndpoint(
            Exception exception, HttpServletRequest request
    ) {
        return response(ErrorCode.RESOURCE_NOT_FOUND, request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request
    ) {
        return response(ErrorCode.METHOD_NOT_ALLOWED, request, List.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleInvalidParameters(
            Exception exception, HttpServletRequest request
    ) {
        return response(ErrorCode.INVALID_REQUEST, request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(
            Exception exception, HttpServletRequest request
    ) {
        LOGGER.error("Unhandled request failure", exception);
        return response(ErrorCode.INTERNAL_ERROR, request, List.of());
    }

    private ResponseEntity<ApiError> response(
            ErrorCode errorCode, HttpServletRequest request, List<FieldViolation> violations
    ) {
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage(errorCode.messageKey(), null, locale);
        ApiError body = new ApiError(
                errorCode.name(), message, Instant.now(clock), request.getRequestURI(), violations
        );
        return ResponseEntity.status(errorCode.status()).body(body);
    }
}

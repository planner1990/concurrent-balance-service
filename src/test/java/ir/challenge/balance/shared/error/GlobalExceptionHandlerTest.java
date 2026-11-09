package ir.challenge.balance.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String REQUEST_PATH = "/api/v1/accounts";
    private static final String MESSAGE_BASENAME = "i18n/messages";

    private GlobalExceptionHandler handler;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename(MESSAGE_BASENAME);
        messageSource.setDefaultEncoding("UTF-8");
        handler = new GlobalExceptionHandler(messageSource, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
        request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(REQUEST_PATH);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void applicationExceptionUsesConsistentHttpStatusAndPayload() {
        ResponseEntity<ApiError> response = handler.handleApplicationException(
                new ApplicationException(ErrorCode.CONFLICT), request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.CONFLICT.name());
        assertThat(response.getBody().timestamp()).isEqualTo(FIXED_INSTANT);
        assertThat(response.getBody().path()).isEqualTo(REQUEST_PATH);
        assertThat(response.getBody().violations()).isEmpty();
    }

    @Test
    void applicationExceptionResolvesFarsiMessages() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("fa"));

        ResponseEntity<ApiError> response = handler.handleApplicationException(
                new ApplicationException(ErrorCode.INVALID_REQUEST), request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("درخواست معتبر نیست.");
    }

    @Test
    void unexpectedExceptionDoesNotExposeTechnicalDetails() {
        ResponseEntity<ApiError> response = handler.handleUnexpectedException(
                new IllegalStateException("secret infrastructure detail"), request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain("secret infrastructure detail");
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }
}

package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Agrupa los escenarios de prueba de {@code ApiExceptionHandlerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class ApiExceptionHandlerTest {
    /**
     * Dato compartido {@code handler} para los escenarios de prueba.
     */
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void includesRetryAfterForRateAndCapacityErrors() {
        var limited = handler.rateLimited(new RateLimitException("rate_limited", "busy", 60));
        var busy = handler.unavailable(
                new ServiceUnavailableException("service_busy", "busy", 1));
        var databaseBusy = handler.databaseUnavailable(
                new CannotCreateTransactionException("pool exhausted"));
        var authenticationBusy = handler.authenticationServiceUnavailable(
                new InternalAuthenticationServiceException("pool exhausted"));

        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(limited.getBody().code()).isEqualTo("rate_limited");
        assertThat(busy.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(busy.getBody().code()).isEqualTo("service_busy");
        assertThat(databaseBusy.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(databaseBusy.getBody().code()).isEqualTo("service_busy");
        assertThat(authenticationBusy.getStatusCode().value()).isEqualTo(503);
        assertThat(authenticationBusy.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(authenticationBusy.getBody().code()).isEqualTo("service_busy");
    }

    /**
     * Comprueba el escenario {@code mapsDatabaseLockContentionToConflict}.
     */
    @Test
    void mapsDatabaseLockContentionToConflict() {
        var response = handler.databaseBusy(new CannotAcquireLockException("deadlock"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("database_busy");
    }

    /**
     * Comprueba el escenario {@code mapsUnreadableJsonToBadRequest}.
     */
    @Test
    void mapsUnreadableJsonToBadRequest() {
        var response = handler.invalidJson(new HttpMessageNotReadableException(
                "invalid", new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("invalid_json");
    }

    /**
     * Comprueba el escenario {@code
     * ignoresExpectedClientDisconnectsWithoutCreatingAnotherResponse}.
     */
    @Test
    void ignoresExpectedClientDisconnectsWithoutCreatingAnotherResponse() {
        handler.clientDisconnected(new AsyncRequestNotUsableException("Broken pipe"));
    }
}

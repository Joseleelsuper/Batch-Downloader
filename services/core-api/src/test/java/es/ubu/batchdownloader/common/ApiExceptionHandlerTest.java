package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
        var response = handler.invalidJson(new HttpMessageNotReadableException("invalid"));

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

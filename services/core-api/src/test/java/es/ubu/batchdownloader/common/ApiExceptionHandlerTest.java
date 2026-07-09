package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.converter.HttpMessageNotReadableException;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void mapsDatabaseLockContentionToConflict() {
        var response = handler.databaseBusy(new CannotAcquireLockException("deadlock"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("database_busy");
    }

    @Test
    void mapsUnreadableJsonToBadRequest() {
        var response = handler.invalidJson(new HttpMessageNotReadableException("invalid"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("invalid_json");
    }
}

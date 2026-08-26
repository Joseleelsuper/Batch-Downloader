package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.application.TemporaryDiskCapacity;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

/** Verifica el contrato HTTP interno de capacidad temporal. */
class WorkerCapacityControllerTest {
    @TempDir Path temporary;

    @Test
    void rejectsInvalidInternalToken() {
        WorkerCapacityController controller = controller(mock(TemporaryDiskCapacity.class));

        assertThat(controller.check("wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsRequestsWhenTheInternalTokenIsNotConfigured() {
        WorkerCapacityController controller = controller(mock(TemporaryDiskCapacity.class), "");

        assertThat(controller.check("").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.check(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void returnsStorageBusyWithRetryAfter() {
        TemporaryDiskCapacity capacity = mock(TemporaryDiskCapacity.class);
        doThrow(new InfrastructureException("storage_busy", new IllegalStateException()))
                .when(capacity).requireAvailable(any(Path.class));
        WorkerCapacityController controller = controller(capacity);

        var response = controller.check("token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(response.getBody()).isInstanceOfSatisfying(
                Map.class,
                body -> assertThat(body.get("code")).isEqualTo("storage_busy"));
    }

    private WorkerCapacityController controller(TemporaryDiskCapacity capacity) {
        return controller(capacity, "token");
    }

    private WorkerCapacityController controller(
            TemporaryDiskCapacity capacity,
            String serviceToken) {
        DownloadProperties properties = new DownloadProperties(
                100,
                DataSize.ofGigabytes(4),
                DataSize.ofGigabytes(20),
                5,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                8,
                Duration.ofMinutes(30),
                temporary.toString());
        return new WorkerCapacityController(
                capacity,
                properties,
                new CoreApiProperties("http://core", serviceToken, Duration.ofSeconds(1)));
    }
}

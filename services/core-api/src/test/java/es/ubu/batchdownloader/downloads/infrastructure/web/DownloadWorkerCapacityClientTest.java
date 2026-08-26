package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.ServiceUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifica la traducción estable de la admisión del worker. */
class DownloadWorkerCapacityClientTest {
    @Test
    void acceptsAvailableStorage() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        DownloadWorkerCapacityClient client = client(http);

        assertThatCode(client::requireAvailable).doesNotThrowAnyException();
    }

    @Test
    void exposesStorageBusyAndRetryAfter() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("Retry-After", List.of("7")), (name, value) -> true));
        when(http.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        DownloadWorkerCapacityClient client = client(http);

        assertThatThrownBy(client::requireAvailable)
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("storage_busy");
                    assertThat(exception.retryAfterSeconds()).isEqualTo(7);
                });
    }

    private DownloadWorkerCapacityClient client(HttpClient http) {
        return new DownloadWorkerCapacityClient(
                http,
                URI.create("http://worker/internal/v1/capacity/check"),
                Duration.ofSeconds(1),
                "token");
    }
}

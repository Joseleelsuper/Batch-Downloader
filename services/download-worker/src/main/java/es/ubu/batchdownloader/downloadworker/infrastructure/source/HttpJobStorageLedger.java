package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.ports.JobStorageLedger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

/** Adaptador autenticado del presupuesto compartido, cerrado ante fallos de Core. */
public final class HttpJobStorageLedger implements JobStorageLedger {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final CoreApiProperties properties;

    public HttpJobStorageLedger(HttpClient client, ObjectMapper mapper, CoreApiProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public State update(UUID jobId, UUID attemptId, String action, long bytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "")
                            + "/internal/v1/download-jobs/" + jobId + "/storage"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Service-Token", properties.serviceToken())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("attemptId", attemptId, "action", action, "bytes", bytes))))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Storage ledger HTTP " + response.statusCode());
            }
            State state = mapper.readValue(response.body(), State.class);
            if (state == null || state.budgetBytes() <= 0 || state.reservedBytes() < 0
                    || state.reservedBytes() > state.budgetBytes()) {
                throw new IOException("Invalid storage ledger response");
            }
            return state;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("storage_ledger_interrupted", exception);
        } catch (IOException exception) {
            throw new InfrastructureException("storage_ledger_unavailable", exception);
        }
    }
}

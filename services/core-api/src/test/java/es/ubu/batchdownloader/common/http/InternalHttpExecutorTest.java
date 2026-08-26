package es.ubu.batchdownloader.common.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifica que las políticas comunes se componen sin contaminar al cliente funcional. */
class InternalHttpExecutorTest {

    @Test
    void composesTokenTimeoutAndLowCardinalityMetrics() {
        AtomicReference<InternalHttpRequest> captured = new AtomicReference<>();
        InternalHttpExecutor executor = request -> {
            captured.set(request);
            return new InternalHttpResponse(
                    204,
                    HttpHeaders.of(Map.of(), (_name, _value) -> true),
                    "");
        };
        executor = new ServiceTokenInternalHttpExecutor(executor, "shared-secret");
        executor = new TimeoutInternalHttpExecutor(executor, Duration.ofSeconds(3));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        executor = new MeteredInternalHttpExecutor(executor, registry);

        executor.execute(new InternalHttpRequest(
                "semantic",
                "search",
                "POST",
                URI.create("http://semantic-service/internal/v1/semantic/search"),
                "{}"));

        assertThat(captured.get().headers())
                .containsEntry("X-Internal-Service-Token", "shared-secret");
        assertThat(captured.get().timeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(registry.get("core_internal_http")
                .tags("service", "semantic", "operation", "search", "outcome", "2xx")
                .timer()
                .count()).isEqualTo(1);
    }
}

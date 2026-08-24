package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifica que la auditoría siga siendo no bloqueante sin ocultar sus fallos. */
class AdminAuditServiceTest {
    @Test
    void exposesPersistenceFailuresThroughAMetricWithoutBreakingTheOperation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(jdbc)
                .update(anyString(), any(Object[].class));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        AdminAuditService audit = new AdminAuditService(
                jdbc,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC),
                metrics);

        assertThatCode(() -> audit.record(
                        "00000000-0000-0000-0000-000000000001",
                        "app.update",
                        "app",
                        "app-id",
                        Map.of()))
                .doesNotThrowAnyException();
        assertThat(metrics.get("admin.audit.failures").counter().count()).isEqualTo(1.0);
    }
}

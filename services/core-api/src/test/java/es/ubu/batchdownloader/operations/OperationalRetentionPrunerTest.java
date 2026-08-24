package es.ubu.batchdownloader.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Prueba las ventanas, lotes y exclusión del trabajo pendiente. */
class OperationalRetentionPrunerTest {

    @Test
    void pruneUsesExactCutoffsAndBoundedTerminalPredicates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Timestamp.class), eq(500))).thenReturn(3, 2, 1);
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
        OperationalRetentionPruner pruner = new OperationalRetentionPruner(
                jdbc,
                clock,
                new SimpleMeterRegistry(),
                mock(PlatformTransactionManager.class));

        OperationalRetentionPruner.RetentionResult result = pruner.prune();

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> cutoffs = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc, org.mockito.Mockito.times(3))
                .update(statements.capture(), cutoffs.capture(), eq(500));
        assertThat(result.total()).isEqualTo(6);
        assertThat(statements.getAllValues()).allSatisfy(sql -> assertThat(sql)
                .contains("ORDER BY", "LIMIT ?")
                .doesNotContain("admin_audit_logs"));
        assertThat(statements.getAllValues().get(0))
                .contains("published_at IS NOT NULL")
                .doesNotContain("published_at IS NULL");
        assertThat(statements.getAllValues().get(1))
                .contains("processed_at IS NOT NULL")
                .doesNotContain("processed_at IS NULL");
        assertThat(statements.getAllValues().get(2))
                .contains("status IN ('READY', 'PARTIAL', 'MANUAL_ONLY', 'FAILED', 'CANCELLED', 'EXPIRED')")
                .doesNotContain("QUEUED", "RESOLVING", "DOWNLOADING", "PACKAGING");
        assertThat(cutoffs.getAllValues()).containsExactly(
                Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-07-24T00:00:00Z")));
    }
}

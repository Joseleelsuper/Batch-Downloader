package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Prueba la retención de idempotencias del worker de descarga. */
class DownloadInboxRetentionPrunerTest {

    @Test
    void prunesOnlyCompletedRowsOlderThanSevenDays() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(
                        anyString(), eq(String.class), any(Timestamp.class), eq(500)))
                .thenReturn(List.of("event-a", "event-b"));
        when(jdbc.update(anyString(), anyString())).thenReturn(1);
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

        int affected = new DownloadInboxRetentionPruner(
                        jdbc, clock, new SimpleMeterRegistry())
                .prune();

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).queryForList(query.capture(), eq(String.class), cutoff.capture(), eq(500));
        assertThat(query.getValue())
                .contains("status = 'COMPLETED'", "completed_at IS NOT NULL", "LIMIT ?")
                .doesNotContain("PROCESSING");
        assertThat(cutoff.getValue())
                .isEqualTo(Timestamp.from(Instant.parse("2026-08-16T00:00:00Z")));
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                eq("DELETE FROM download_inbox WHERE event_id = ? AND status = 'COMPLETED'"),
                anyString());
        assertThat(affected).isEqualTo(2);
    }
}

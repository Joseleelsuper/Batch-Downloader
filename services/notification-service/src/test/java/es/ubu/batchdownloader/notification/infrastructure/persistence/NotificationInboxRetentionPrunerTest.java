package es.ubu.batchdownloader.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Prueba la retención de idempotencias del servicio de notificaciones. */
class NotificationInboxRetentionPrunerTest {

    @Test
    void prunesOnlyProcessedRowsOlderThanSevenDays() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Long.class), eq(500)))
                .thenReturn(List.of("event-a", "event-b"));
        when(jdbc.update(anyString(), anyString())).thenReturn(1);
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

        int affected = new NotificationInboxRetentionPruner(
                        jdbc, clock, new SimpleMeterRegistry())
                .prune();

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(jdbc).queryForList(query.capture(), eq(String.class), cutoff.capture(), eq(500));
        assertThat(query.getValue())
                .contains("status = 'PROCESSED'", "processed_at_epoch_ms IS NOT NULL", "LIMIT ?")
                .doesNotContain("PROCESSING", "FAILED");
        assertThat(cutoff.getValue())
                .isEqualTo(Instant.parse("2026-08-16T00:00:00Z").toEpochMilli());
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                eq("DELETE FROM notification_inbox WHERE event_id = ? AND status = 'PROCESSED'"),
                anyString());
        assertThat(affected).isEqualTo(2);
    }
}

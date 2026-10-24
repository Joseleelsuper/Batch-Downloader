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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

/**
 * Comprueba el límite temporal de la limpieza sin perder fallos ni reservas vigentes.
 *
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.persistence.NotificationInboxRetentionPruner
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class NotificationInboxRetentionPrunerTest {

    /**
     * Comprueba que solo desaparecen confirmaciones fuera de plazo y se conservan las
     * demás filas.
     */
    @Test
    void prunesOnlyProcessedRowsOutsideRetention() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), any(Long.class), eq(500)))
                .thenReturn(List.of("event-a", "event-b"));
        when(jdbc.update(anyString(), anyString(), any(Long.class))).thenReturn(1);
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);

        int affected = new NotificationInboxRetentionPruner(
                        jdbc, clock, new SimpleMeterRegistry(), Duration.ofMinutes(1), Duration.ofHours(24))
                .prune();

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> cutoff = ArgumentCaptor.forClass(Long.class);
        verify(jdbc).queryForList(query.capture(), eq(String.class), cutoff.capture(), eq(500));
        assertThat(query.getValue())
                .contains("status IN ('PROCESSED', 'FAILED')", "COALESCE", "LIMIT ?")
                .doesNotContain("PROCESSING");
        assertThat(cutoff.getValue())
                .isEqualTo(Instant.parse("2026-08-22T00:01:00Z").toEpochMilli());
        verify(jdbc, org.mockito.Mockito.times(2)).update(
                eq("DELETE FROM notification_inbox WHERE event_id = ? AND status IN ('PROCESSED', 'FAILED') "
                        + "AND COALESCE(processed_at_epoch_ms, received_at_epoch_ms) < ?"),
                anyString(), any(Long.class));
        assertThat(affected).isEqualTo(2);
    }

    @Test
    void removesOldFailuresOnRealH2WithoutTouchingActiveOrRecentEntries() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:notification_retention;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        for (String status : List.of("PROCESSED", "FAILED", "PROCESSING")) {
            for (String age : List.of("old", "recent")) {
                long received = now.minusSeconds(age.equals("old") ? 172800 : 60).toEpochMilli();
                jdbc.update("INSERT INTO notification_inbox (event_id,event_type,status,attempt_count,received_at_epoch_ms,processed_at_epoch_ms,last_error) VALUES (?,?,?,?,?,?,?)",
                        status + age, "notification.email.requested", status, 1, received,
                        status.equals("PROCESSED") ? received : null, "persona@example.com");
            }
        }
        int removed = new NotificationInboxRetentionPruner(jdbc, Clock.fixed(now, ZoneOffset.UTC),
                new SimpleMeterRegistry(), Duration.ofMinutes(1), Duration.ofHours(24)).prune();
        assertThat(removed).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT event_id FROM notification_inbox", String.class))
                .containsExactlyInAnyOrder("PROCESSEDrecent", "FAILEDrecent", "PROCESSINGold", "PROCESSINGrecent");
    }
}

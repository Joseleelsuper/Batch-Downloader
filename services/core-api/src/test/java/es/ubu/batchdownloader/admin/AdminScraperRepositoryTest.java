package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;

import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperRunSummary;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Comprueba que el mantenimiento administrativo respeta la política de retención. */
class AdminScraperRepositoryTest {

    /** La consulta de ejecución actual aprovecha los índices y evita ordenar el historial completo. */
    @Test
    void currentPrefersRunningWithoutSortingTheWholeHistory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScraperRunSummary running = mock(ScraperRunSummary.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ScraperRunSummary>>any()))
                .thenReturn(List.of(running));

        ScraperRunSummary result = new AdminScraperRepository(jdbc, Clock.systemUTC()).current();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<ScraperRunSummary>>any());
        assertThat(result).isSameAs(running);
        assertThat(sql.getValue())
                .contains("WHERE status = 'running'", "ORDER BY started_at DESC", "LIMIT 1")
                .doesNotContain("ORDER BY (status = 'running')");
    }

    /** Devuelve la ejecución más reciente cuando no hay ninguna ejecución activa. */
    @Test
    void currentFallsBackToTheLatestRunWhenNothingIsRunning() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ScraperRunSummary latest = mock(ScraperRunSummary.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ScraperRunSummary>>any()))
                .thenReturn(List.of(), List.of(latest));

        ScraperRunSummary result = new AdminScraperRepository(jdbc, Clock.systemUTC()).current();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(
                sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<ScraperRunSummary>>any());
        assertThat(result).isSameAs(latest);
        assertThat(sql.getAllValues().get(0)).contains("WHERE status = 'running'");
        assertThat(sql.getAllValues().get(1))
                .contains("ORDER BY started_at DESC", "LIMIT 1")
                .doesNotContain("WHERE status = 'running'");
    }

    /** La poda manual comparte límite y ventana con el pruner automático. */
    @Test
    void terminalPrunerIsBoundedAndNeverDeletesLeasedRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
        when(jdbc.update(anyString(), any(Timestamp.class), eq(500)))
                .thenReturn(7);

        int affected = new AdminScraperRepository(jdbc, clock).pruneTerminalQueueItems();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).update(sql.capture(), cutoff.capture(), eq(500));
        assertThat(affected).isEqualTo(7);
        assertThat(sql.getValue())
                .contains("status IN ('completed', 'discarded')")
                .contains("lease_owner IS NULL", "lease_expires_at IS NULL")
                .contains("ORDER BY updated_at ASC, id ASC", "LIMIT ?")
                .doesNotContain("status IN ('queued'", "status = 'in_progress'");
        assertThat(cutoff.getValue())
                .isEqualTo(Timestamp.from(Instant.parse("2026-07-24T00:00:00Z")));
    }
}

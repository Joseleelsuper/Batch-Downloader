package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;

import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperRunSummary;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ResolverLogItem;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Comprueba que el mantenimiento administrativo respeta la política de retención. */
class AdminScraperRepositoryTest {

    /** Ordena sólo UUID y fechas antes de cargar los metadatos amplios de los logs seleccionados. */
    @Test
    void logsKeepChronologicalOrderAndLimitWithWideMetadata() {
        String database = "jdbc:h2:mem:resolver-logs-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(database, "sa", ""));
        jdbc.execute("""
                CREATE TABLE resolver_logs (
                    id BINARY(16) PRIMARY KEY,
                    phase VARCHAR(50), status VARCHAR(32), message VARCHAR(2000),
                    safe_metadata CLOB, created_at TIMESTAMP
                )
                """);
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        for (int index = 0; index < 3; index++) {
            UUID id = List.of(oldest, middle, newest).get(index);
            jdbc.update(
                    "INSERT INTO resolver_logs VALUES (?, 'resolver', 'ok', NULL, ?, ?)",
                    UuidBytes.fromUuid(id),
                    "x".repeat(20_000),
                    Timestamp.valueOf("2026-10-13 10:00:0" + index));
        }

        List<ResolverLogItem> logs = new AdminScraperRepository(jdbc, Clock.systemUTC()).logs(2);

        assertThat(logs).extracting(ResolverLogItem::id)
                .containsExactly(newest.toString(), middle.toString());
    }

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

    /** La poda manual limita el lote y conserva las tareas activas o arrendadas. */
    @Test
    void terminalPrunerIsBoundedAndNeverDeletesLeasedRows() {
        String database = "jdbc:h2:mem:queue-prune-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(database, "sa", ""));
        jdbc.execute("""
                CREATE TABLE scraper_work_items (
                    id BINARY(16) PRIMARY KEY, status VARCHAR(32), updated_at TIMESTAMP,
                    lease_owner VARCHAR(120), lease_expires_at TIMESTAMP
                )
                """);
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
        Timestamp old = Timestamp.from(Instant.parse("2026-07-01T00:00:00Z"));
        for (int index = 0; index < 501; index++) {
            jdbc.update("INSERT INTO scraper_work_items VALUES (?, 'completed', ?, NULL, NULL)",
                    UuidBytes.fromUuid(UUID.randomUUID()), old);
        }
        jdbc.update("INSERT INTO scraper_work_items VALUES (?, 'completed', ?, 'worker', ?)",
                UuidBytes.fromUuid(UUID.randomUUID()), old, old);
        jdbc.update("INSERT INTO scraper_work_items VALUES (?, 'queued', ?, NULL, NULL)",
                UuidBytes.fromUuid(UUID.randomUUID()), old);

        int affected = new AdminScraperRepository(jdbc, clock).pruneTerminalQueueItems();

        assertThat(affected).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scraper_work_items", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraper_work_items WHERE lease_owner = 'worker'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scraper_work_items WHERE status = 'queued'", Integer.class))
                .isEqualTo(1);
    }
}

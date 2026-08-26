package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** Comprueba que el mantenimiento administrativo respeta la política de retención. */
class AdminScraperRepositoryTest {

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

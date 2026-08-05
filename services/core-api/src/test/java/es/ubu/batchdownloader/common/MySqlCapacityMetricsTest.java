package es.ubu.batchdownloader.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Verifica que el estado global de MySQL se expone sin abrir otro pool. */
class MySqlCapacityMetricsTest {
    @Test
    void exposesSlowQueriesAndConnectionPressure() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
                    RowCallbackHandler handler = invocation.getArgument(1);
                    handler.processRow(row("Slow_queries", 7));
                    handler.processRow(row("Threads_connected", 8));
                    handler.processRow(row("Threads_running", 3));
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MySqlCapacityMetrics metrics = new MySqlCapacityMetrics(jdbc, registry);

        metrics.refresh();

        assertThat(registry.get("batch_mysql_slow_queries").gauge().value()).isEqualTo(7);
        assertThat(registry.get("batch_mysql_threads_connected").gauge().value()).isEqualTo(8);
        assertThat(registry.get("batch_mysql_threads_running").gauge().value()).isEqualTo(3);
    }

    /** Crea una fila mínima de SHOW GLOBAL STATUS. */
    private static ResultSet row(String name, long value) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString(1)).thenReturn(name);
        when(row.getLong(2)).thenReturn(value);
        return row;
    }
}

package es.ubu.batchdownloader.common;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publica el estado global mínimo de MySQL usando el pool existente de Core. */
@Component
final class MySqlCapacityMetrics {
    /** Registro de incidencias de lectura sin interrumpir la aplicación. */
    private static final Logger LOGGER = LoggerFactory.getLogger(MySqlCapacityMetrics.class);
    /** Consultas que han superado {@code long_query_time}. */
    private final AtomicLong slowQueries = new AtomicLong();
    /** Conexiones abiertas actualmente en MySQL. */
    private final AtomicLong connectedThreads = new AtomicLong();
    /** Consultas que MySQL está ejecutando actualmente. */
    private final AtomicLong runningThreads = new AtomicLong();
    /** Acceso ligero al estado global de MySQL. */
    private final JdbcTemplate jdbc;

    /** Inicializa los medidores Prometheus sin crear otro pool. */
    MySqlCapacityMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("batch_mysql_slow_queries", slowQueries);
        registry.gauge("batch_mysql_threads_connected", connectedThreads);
        registry.gauge("batch_mysql_threads_running", runningThreads);
    }

    /** Refresca una sola vez los tres contadores globales. */
    @Scheduled(
            initialDelayString = "${app.metrics.mysql-status-delay:15s}",
            fixedDelayString = "${app.metrics.mysql-status-delay:15s}")
    void refresh() {
        try {
            jdbc.query(
                    """
                    SHOW GLOBAL STATUS
                    WHERE Variable_name IN ('Slow_queries', 'Threads_connected', 'Threads_running')
                    """,
                    (RowCallbackHandler) row -> update(row.getString(1), row.getLong(2)));
        } catch (DataAccessException exception) {
            LOGGER.debug("Could not refresh MySQL capacity metrics", exception);
        }
    }

    /** Copia cada variable reconocida en su medidor estable. */
    private void update(String name, long value) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "slow_queries" -> slowQueries.set(value);
            case "threads_connected" -> connectedThreads.set(value);
            case "threads_running" -> runningThreads.set(value);
            default -> {
                // La consulta limita las filas; se ignoran extensiones inesperadas del servidor.
            }
        }
    }
}

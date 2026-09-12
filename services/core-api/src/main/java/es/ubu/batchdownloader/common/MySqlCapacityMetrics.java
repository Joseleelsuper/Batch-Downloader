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

/**
 * Publica contadores globales de consultas lentas y conexiones de MySQL sin convertir un fallo de
 * observación en fallo del servicio.
 *
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
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

    /**
     * Registra gauges de consultas lentas, hilos conectados y hilos ejecutándose sobre contadores
     * atómicos.
     *
     * @param jdbc Acceso SQL utilizado para consultar los contadores globales de MySQL.
     * @param registry Registro donde se publica duración y resultado sin incluir contenido ni URLs.
     */
    MySqlCapacityMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("batch_mysql_slow_queries", slowQueries);
        registry.gauge("batch_mysql_threads_connected", connectedThreads);
        registry.gauge("batch_mysql_threads_running", runningThreads);
    }

    /**
     * Consulta los tres estados globales a intervalos configurados y conserva la última muestra si
     * MySQL no puede responder.
     */
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

    /**
     * Actualiza el contador correspondiente e ignora nombres de estado desconocidos.
     *
     * @param name Slow_queries, Threads_connected o Threads_running, sin distinguir mayúsculas.
     * @param value Último valor acumulado o instantáneo comunicado por MySQL.
     */
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

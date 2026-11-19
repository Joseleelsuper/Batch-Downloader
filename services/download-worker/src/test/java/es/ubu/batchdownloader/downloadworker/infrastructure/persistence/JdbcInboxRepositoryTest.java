package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Caracteriza reserva, finalización y liberación de eventos sobre una base H2 independiente por
 * prueba.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.persistence.JdbcInboxRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de integración y mensajería
 */
class JdbcInboxRepositoryTest {
    /**
     * Dato compartido {@code repository} para los escenarios de prueba.
     */
    private JdbcInboxRepository repository;
    private JdbcTemplate jdbc;

    /**
     * Crea una base en memoria con la tabla del inbox y un repositorio JDBC para aislar las
     * reservas de cada escenario.
     */
    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE download_inbox (
                    event_id VARCHAR(64) PRIMARY KEY,
                    status VARCHAR(24) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE NULL,
                    pending_result CLOB NULL,
                    job_id VARCHAR(36) NULL
                )
                """);
        repository = new JdbcInboxRepository(jdbc, Clock.systemUTC());
    }

    /**
     * Reserva un evento, rechaza un segundo intento y comprueba que completar la reserva impide
     * recuperarla incluso con duración cero.
     */
    @Test
    void processesAnEventOnlyOnceAfterCompletion() {
        UUID eventId = UUID.randomUUID();

        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isFalse();
        repository.complete(eventId);
        assertThat(repository.tryStart(eventId, Duration.ZERO)).isFalse();
    }

    /**
     * Libera una reserva pendiente y comprueba que el mismo evento puede reservarse de nuevo para
     * el reintento.
     */
    @Test
    void releaseAllowsRabbitRetryToClaimAgain() {
        UUID eventId = UUID.randomUUID();
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();

        repository.release(eventId);

        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
    }

    @Test
    void sealedReadySurvivesFailureReleaseAndWorkerRestart() {
        UUID eventId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
        repository.saveReady(eventId, jobId, "{\"result\":\"READY\"}");
        repository.release(eventId);
        assertThat(repository.pendingReady(eventId)).isEqualTo("{\"result\":\"READY\"}");
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
        repository.complete(eventId);
        assertThat(repository.pendingReady(eventId)).isNull();
        assertThat(repository.tryStart(eventId, Duration.ZERO)).isFalse();
        assertThat(repository.trackedJobs()).containsExactly(jobId);
        repository.clearReady(jobId);
        assertThat(repository.trackedJobs()).isEmpty();
    }

    @Test
    void remembersJobsWithOnlyMultipartAcrossFailureAndRestartUntilConfirmedCleanup() {
        UUID eventId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
        repository.rememberJob(eventId, jobId);
        repository.release(eventId);
        assertThat(repository.trackedJobs()).containsExactly(jobId);
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
        repository.clearReady(jobId);
        repository.complete(eventId);
        assertThat(repository.trackedJobs()).isEmpty();
    }

    @Test void exclusiveRecoveryDoesNotDependOnThePreviousWritersWallClock() {
        UUID id = UUID.randomUUID();
        var futureWriter = new JdbcInboxRepository(jdbc, Clock.offset(Clock.systemUTC(), Duration.ofDays(1)));
        assertThat(futureWriter.tryStart(id, Duration.ofMinutes(30))).isTrue();
        assertThat(repository.tryStart(id, Duration.ofMinutes(30))).isFalse();
        // El llamador con duración cero ya posee el bloqueo exclusivo del núcleo.
        assertThat(repository.tryStart(id, Duration.ZERO)).isTrue();
    }
}

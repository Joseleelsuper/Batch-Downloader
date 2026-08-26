package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Implementa el componente {@code JpaDownloadJobStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
class JpaDownloadJobStore implements DownloadJobStore {
    /** Estados que ya no ocupan una plaza de admisión. */
    private static final List<DownloadJobStatus> TERMINAL_STATUSES = List.of(
            DownloadJobStatus.READY,
            DownloadJobStatus.PARTIAL,
            DownloadJobStatus.MANUAL_ONLY,
            DownloadJobStatus.FAILED,
            DownloadJobStatus.CANCELLED,
            DownloadJobStatus.EXPIRED);
    /**
     * Estado {@code repository} mantenido por {@code JpaDownloadJobStore}.
     */
    private final SpringDataDownloadJobRepository repository;
    /** Ejecuta las actualizaciones dirigidas de progreso. */
    private final JdbcTemplate jdbc;

    /**
     * Inicializa una instancia de {@code JpaDownloadJobStore}.
     *
     * @param repository Repositorio utilizado por la operación.
     */
    JpaDownloadJobStore(SpringDataDownloadJobRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void lockAdmission() {
        jdbc.queryForObject(
                "SELECT id FROM download_job_capacity_guard WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    /**
     * Guarda el recurso solicitado mediante {@code save}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     * @return Resultado producido por {@code save}.
     */
    @Override
    public DownloadJob save(DownloadJob job) {
        DownloadJobEntity entity = repository.findById(job.id()).orElseGet(() -> DownloadJobEntity.from(job));
        entity.updateFrom(job);
        return repository.save(entity).toDomain();
    }

    /**
     * Busca el resultado solicitado mediante {@code findById}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @return Resultado producido por {@code findById}.
     */
    @Override
    public Optional<DownloadJob> findById(UUID id) {
        return repository.findById(id).map(DownloadJobEntity::toDomain);
    }

    /**
     * Busca el resultado solicitado mediante {@code findDownloadableExpiredBefore}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    @Override
    public List<DownloadJob> findDownloadableExpiredBefore(Instant now) {
        return repository.findByStatusInAndExpiresAtLessThanEqual(
                        List.of(
                                DownloadJobStatus.READY,
                                DownloadJobStatus.PARTIAL,
                                DownloadJobStatus.MANUAL_ONLY),
                        now)
                .stream().map(DownloadJobEntity::toDomain).toList();
    }

    /**
     * Implementa {@code countAnonymousNonTerminal} para {@code JpaDownloadJobStore}.
     *
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    @Override
    public long countAnonymousNonTerminal(String anonymousOwnerHash) {
        return repository.countByAnonymousOwnerHashAndStatusNotIn(
                anonymousOwnerHash, TERMINAL_STATUSES);
    }

    /**
     * Implementa {@code countAnonymousCreatedSince} para {@code JpaDownloadJobStore}.
     *
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param createdAfter Valor de {@code createdAfter} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    @Override
    public long countAnonymousCreatedSince(String anonymousOwnerHash, Instant createdAfter) {
        return repository.countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual(anonymousOwnerHash, createdAfter);
    }

    /**
     * Implementa {@code countAnonymousIpCreatedSince} para {@code JpaDownloadJobStore}.
     *
     * @param anonymousIpHash Valor de {@code anonymousIpHash} utilizado por la operación.
     * @param createdAfter Valor de {@code createdAfter} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    @Override
    public long countAnonymousIpCreatedSince(String anonymousIpHash, Instant createdAfter) {
        return repository.countByAnonymousIpHashAndCreatedAtGreaterThanEqual(anonymousIpHash, createdAfter);
    }

    /** {@inheritDoc} */
    @Override
    public long countNonTerminal() {
        return repository.countByStatusNotIn(TERMINAL_STATUSES);
    }

    /** {@inheritDoc} */
    @Override
    public long countNonTerminalByOwner(UUID ownerId) {
        return repository.countByOwnerIdAndStatusNotIn(ownerId, TERMINAL_STATUSES);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<DownloadJob> applyProgress(
            UUID jobId,
            UUID itemId,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode,
            Instant now) {
        jdbc.update(
                """
                UPDATE download_job_items item
                JOIN download_jobs job ON job.id = item.job_id
                SET item.status = ?,
                    item.bytes_downloaded = GREATEST(item.bytes_downloaded, ?),
                    item.sha256 = ?,
                    item.error_code = ?,
                    item.updated_at = ?,
                    item.version = item.version + 1
                WHERE item.id = ?
                  AND item.job_id = ?
                  AND item.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                  AND job.status NOT IN ('READY', 'PARTIAL', 'MANUAL_ONLY', 'FAILED', 'CANCELLED', 'EXPIRED')
                """,
                status.name(),
                Math.max(0, bytesDownloaded),
                sha256,
                errorCode,
                java.sql.Timestamp.from(now),
                itemId.toString(),
                jobId.toString());
        jdbc.update(
                """
                UPDATE download_jobs job
                JOIN (
                    SELECT job_id,
                           SUM(status IN ('COMPLETED', 'FAILED', 'CANCELLED')) AS terminal_count
                    FROM download_job_items
                    WHERE job_id = ?
                    GROUP BY job_id
                ) totals ON totals.job_id = job.id
                SET job.progress = GREATEST(
                        job.progress,
                        FLOOR((totals.terminal_count * 90) / GREATEST(job.accepted_count, 1))),
                    job.status = CASE
                        WHEN totals.terminal_count = job.accepted_count THEN 'PACKAGING'
                        WHEN ? = 'RESOLVING' AND job.status = 'QUEUED' THEN 'RESOLVING'
                        WHEN ? IN ('DOWNLOADING', 'COMPLETED', 'FAILED', 'CANCELLED')
                             AND job.status IN ('QUEUED', 'RESOLVING') THEN 'DOWNLOADING'
                        ELSE job.status
                    END,
                    job.updated_at = ?,
                    job.version = job.version + 1
                WHERE job.id = ?
                  AND job.status NOT IN ('READY', 'PARTIAL', 'MANUAL_ONLY', 'FAILED', 'CANCELLED', 'EXPIRED')
                """,
                jobId.toString(),
                status.name(),
                status.name(),
                java.sql.Timestamp.from(now),
                jobId.toString());
        return findById(jobId);
    }
}

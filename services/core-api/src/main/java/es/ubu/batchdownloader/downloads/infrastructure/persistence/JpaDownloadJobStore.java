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
 * Combina JPA para agregados y SQL para cuotas y progreso atómico sin compartir estado entre
 * trabajadores.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobStore
 * @see es.ubu.batchdownloader.downloads.infrastructure.persistence.DownloadJobEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Repository
class JpaDownloadJobStore implements DownloadJobStore {
    /**
     * Fuerza la inserción JPA del trabajo antes de guardar su destino y la lista de dependencias en
     * la misma transacción.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param context Destino Linux y dependencias añadidas que se adjuntan a la vista del trabajo.
     */
    @Override
    public void saveLinuxContext(UUID jobId,
            es.ubu.batchdownloader.downloads.application.DownloadJobView.LinuxContext context) {
        repository.flush();
        jdbc.update("INSERT INTO download_job_linux_context(job_id, linux_target, architecture, dependencies) VALUES (?, ?, ?, ?)",
                jobId.toString(), context.target(), context.architecture(),
                context.addedDependencyAppIds().stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")));
    }

    /**
     * Reconstruye destino, arquitectura y UUID de dependencias a partir del contexto persistido.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return contexto guardado o null cuando el trabajo no tiene uno.
     */
    @Override
    public es.ubu.batchdownloader.downloads.application.DownloadJobView.LinuxContext linuxContext(UUID jobId) {
        var rows = jdbc.query("SELECT linux_target, architecture, dependencies FROM download_job_linux_context WHERE job_id = ?",
                (row, index) -> new es.ubu.batchdownloader.downloads.application.DownloadJobView.LinuxContext(
                        row.getString(1), row.getString(2), row.getString(3).isBlank() ? List.of()
                            : java.util.Arrays.stream(row.getString(3).split(",")).map(UUID::fromString).toList()),
                jobId.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
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
     * Conecta el repositorio de agregados y SQL que participa en la misma transacción de Spring.
     *
     * @param repository Repositorio JPA de trabajos que carga sus elementos y aplica concurrencia
     *     optimista.
     * @param jdbc Acceso SQL que participa en la transacción de Spring del llamador.
     */
    JpaDownloadJobStore(SpringDataDownloadJobRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /**
     * Bloquea la fila única de capacidad con SELECT FOR UPDATE hasta el fin de la transacción del
     * llamador.
     */
    @Override
    public void lockAdmission() {
        jdbc.queryForObject(
                "SELECT id FROM download_job_capacity_guard WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    /**
     * Reutiliza la entidad existente o crea una nueva y sincroniza el agregado mediante JPA.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     * @return agregado reconstruido de la entidad guardada.
     */
    @Override
    public DownloadJob save(DownloadJob job) {
        DownloadJobEntity entity = repository.findById(job.id()).orElseGet(() -> DownloadJobEntity.from(job));
        entity.updateFrom(job);
        return repository.save(entity).toDomain();
    }

    /**
     * {@inheritDoc}
     *
     * @param id UUID estable del trabajo o elemento representado.
     */
    @Override
    public Optional<DownloadJob> findById(UUID id) {
        return repository.findById(id).map(DownloadJobEntity::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
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
     * {@inheritDoc}
     *
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     */
    @Override
    public long countAnonymousNonTerminal(String anonymousOwnerHash) {
        return repository.countByAnonymousOwnerHashAndStatusNotIn(
                anonymousOwnerHash, TERMINAL_STATUSES);
    }

    /**
     * {@inheritDoc}
     *
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param createdAfter Instante inicial incluido en la ventana de creaciones.
     */
    @Override
    public long countAnonymousCreatedSince(String anonymousOwnerHash, Instant createdAfter) {
        return repository.countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual(anonymousOwnerHash, createdAfter);
    }

    /**
     * {@inheritDoc}
     *
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param createdAfter Instante inicial incluido en la ventana de creaciones.
     */
    @Override
    public long countAnonymousIpCreatedSince(String anonymousIpHash, Instant createdAfter) {
        return repository.countByAnonymousIpHashAndCreatedAtGreaterThanEqual(anonymousIpHash, createdAfter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long countNonTerminal() {
        return repository.countByStatusNotIn(TERMINAL_STATUSES);
    }

    /**
     * {@inheritDoc}
     *
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     */
    @Override
    public long countNonTerminalByOwner(UUID ownerId) {
        return repository.countByOwnerIdAndStatusNotIn(ownerId, TERMINAL_STATUSES);
    }

    /**
     * Actualiza solo un elemento no terminal, mantiene el máximo de bytes y recalcula estado y
     * progreso del trabajo con SQL.
     * Excluye trabajos terminales de ambas actualizaciones y aumenta las versiones para invalidar
     * escrituras optimistas antiguas.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param itemId UUID de un elemento perteneciente al trabajo indicado.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param bytesDownloaded Bytes transferidos del instalador; el dominio conserva el máximo
     *     recibido.
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return trabajo consultado después de las actualizaciones o vacío si no existe.
     */
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

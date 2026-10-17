package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.downloads.application.DownloadJobAccessService;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Solicita la baja de una cuenta y entrega sus descargas al limpiador durable de ZIPs. */
@Service
public class AccountDeletionService {
    private final JdbcTemplate jdbc;
    private final DownloadJobStore jobs;
    private final DownloadJobAccessService downloads;
    private final DownloadStorageCoordinator storage;
    private final Clock clock;

    /**
     * Conecta la baja de identidad con el bloqueo de admisión y la cancelación durable de descargas.
     *
     * @param jdbc Acceso SQL a los datos de cuenta y a la solicitud de baja pendiente.
     * @param jobs Persistencia de trabajos y bloqueo compartido con su admisión.
     * @param downloads Caso de uso que cancela los trabajos y publica sus eventos durables.
     * @param storage Coordinador que limpia ZIPs y finaliza la baja al terminar.
     * @param clock Reloj usado para registrar la fecha de solicitud.
     */
    public AccountDeletionService(
            JdbcTemplate jdbc, DownloadJobStore jobs, DownloadJobAccessService downloads,
            DownloadStorageCoordinator storage, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.downloads = downloads;
        this.storage = storage;
        this.clock = clock;
    }

    /**
     * Deshabilita la cuenta, elimina de inmediato sus datos visibles y solicita cancelar todos sus
     * trabajos. El coordinador termina de borrar la cuenta cuando los archivos ZIP ya no están en
     * uso y se han limpiado.
     *
     * @param userId UUID canónico de la cuenta USER autenticada.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si la cuenta ya no está habilitada.
     */
    @Transactional
    public void request(UUID userId) {
        // La misma fila serializa esta solicitud con la admisión de nuevos trabajos.
        jobs.lockAdmission();
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_deletion_requests WHERE user_id = ?", Integer.class,
                userId.toString());
        if (pending != null && pending > 0) return;

        List<Boolean> enabled = jdbc.query(
                "SELECT enabled FROM core_users WHERE id = ? AND role = 'USER' FOR UPDATE",
                (rs, row) -> rs.getBoolean(1), userId.toString());
        if (enabled.isEmpty() || !enabled.getFirst()) {
            throw new UnauthorizedException("unauthorized", "La sesión ya no es válida.");
        }

        Timestamp requestedAt = Timestamp.from(clock.instant());
        jdbc.update("UPDATE core_users SET enabled = FALSE, updated_at = ?, version = version + 1 WHERE id = ?",
                requestedAt, userId.toString());
        jdbc.update("INSERT INTO account_deletion_requests (user_id, requested_at) VALUES (?, ?)",
                userId.toString(), requestedAt);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", userId.toString());
        jdbc.update("DELETE FROM bundles WHERE owner_id = ?", userId.toString());
        jdbc.update("DELETE FROM user_download_history WHERE user_id = ?", userId.toString());
        jdbc.update("DELETE FROM download_job_receipts WHERE owner_id = ?", userId.toString());
        jdbc.update("DELETE FROM identity_tokens WHERE user_id = ?", userId.toString());
        jdbc.update("DELETE FROM pending_magic_link_requests WHERE normalized_email = (SELECT normalized_email FROM core_users WHERE id = ?)",
                userId.toString());
        jdbc.update("DELETE FROM core_outbox_events WHERE aggregate_type = 'user' AND aggregate_id = ? AND event_type = 'notification.email.requested' AND published_at IS NULL",
                userId.toString());

        List<UUID> ownedJobs = jdbc.query(
                "SELECT id FROM download_jobs WHERE owner_id = ? FOR UPDATE",
                (rs, row) -> UUID.fromString(rs.getString(1)), userId.toString());
        RequestOwner owner = new RequestOwner(userId, null, null);
        ownedJobs.forEach(jobId -> downloads.cancel(owner, jobId));
        storage.requestAccountJobCleanup(userId);
    }
}

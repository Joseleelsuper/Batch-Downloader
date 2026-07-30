package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implementa el componente {@code JpaDownloadJobStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
class JpaDownloadJobStore implements DownloadJobStore {
    /**
     * Estado {@code repository} mantenido por {@code JpaDownloadJobStore}.
     */
    private final SpringDataDownloadJobRepository repository;

    /**
     * Inicializa una instancia de {@code JpaDownloadJobStore}.
     *
     * @param repository Repositorio utilizado por la operación.
     */
    JpaDownloadJobStore(SpringDataDownloadJobRepository repository) {
        this.repository = repository;
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
                anonymousOwnerHash,
                List.of(
                        DownloadJobStatus.READY,
                        DownloadJobStatus.PARTIAL,
                        DownloadJobStatus.MANUAL_ONLY,
                        DownloadJobStatus.FAILED,
                        DownloadJobStatus.CANCELLED,
                        DownloadJobStatus.EXPIRED));
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
}

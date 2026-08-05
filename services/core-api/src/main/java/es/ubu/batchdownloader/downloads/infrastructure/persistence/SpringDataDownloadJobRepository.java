package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Define el contrato de {@code SpringDataDownloadJobRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
interface SpringDataDownloadJobRepository extends JpaRepository<DownloadJobEntity, UUID> {
    /**
     * Busca el resultado solicitado mediante {@code findById}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @return Resultado producido por {@code findById}.
     */
    @Override
    @EntityGraph(attributePaths = "items")
    Optional<DownloadJobEntity> findById(UUID id);

    /**
     * Busca el resultado solicitado mediante {@code findByStatusInAndExpiresAtLessThanEqual}.
     *
     * @param statuses Valor de {@code statuses} utilizado por la operación.
     * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    @EntityGraph(attributePaths = "items")
    List<DownloadJobEntity> findByStatusInAndExpiresAtLessThanEqual(
            Collection<DownloadJobStatus> statuses, Instant expiresAt);

    /**
     * Ejecuta la operación {@code countByAnonymousOwnerHashAndStatusNotIn}.
     *
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param statuses Valor de {@code statuses} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    long countByAnonymousOwnerHashAndStatusNotIn(
            String anonymousOwnerHash, Collection<DownloadJobStatus> statuses);

    /**
     * Ejecuta la operación {@code countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual}.
     *
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    long countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual(
            String anonymousOwnerHash, Instant createdAt);

    /**
     * Ejecuta la operación {@code countByAnonymousIpHashAndCreatedAtGreaterThanEqual}.
     *
     * @param anonymousIpHash Valor de {@code anonymousIpHash} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    long countByAnonymousIpHashAndCreatedAtGreaterThanEqual(
            String anonymousIpHash, Instant createdAt);

    /** Cuenta los trabajos cuyo estado no pertenece a la colección indicada. */
    long countByStatusNotIn(Collection<DownloadJobStatus> statuses);

    /** Cuenta los trabajos no terminales de una cuenta. */
    long countByOwnerIdAndStatusNotIn(UUID ownerId, Collection<DownloadJobStatus> statuses);
}

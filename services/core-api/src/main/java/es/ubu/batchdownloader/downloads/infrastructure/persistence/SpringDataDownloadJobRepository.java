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
 * Define consultas JPA de agregados completos y recuentos utilizados por admisión y expiración.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.infrastructure.persistence.JpaDownloadJobStore
 * @see es.ubu.batchdownloader.downloads.infrastructure.persistence.DownloadJobEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
interface SpringDataDownloadJobRepository extends JpaRepository<DownloadJobEntity, UUID> {
    /**
     * Carga el trabajo junto a sus elementos mediante un grafo de entidad.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @return entidad completa o vacío.
     */
    @Override
    @EntityGraph(attributePaths = "items")
    Optional<DownloadJobEntity> findById(UUID id);

    /**
     * Carga trabajos y elementos cuyos estados están incluidos y cuyo vencimiento no supera el
     * límite.
     *
     * @param statuses Estados excluidos del recuento o incluidos en la consulta de vencimiento,
     *     según el contrato.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @return agregados candidatos a expiración.
     */
    @EntityGraph(attributePaths = "items")
    List<DownloadJobEntity> findByStatusInAndExpiresAtLessThanEqual(
            Collection<DownloadJobStatus> statuses, Instant expiresAt);

    /**
     * Cuenta trabajos del navegador cuyo estado no está en el conjunto terminal recibido.
     *
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param statuses Estados excluidos del recuento o incluidos en la consulta de vencimiento,
     *     según el contrato.
     * @return número de trabajos que cumplen los filtros.
     */
    long countByAnonymousOwnerHashAndStatusNotIn(
            String anonymousOwnerHash, Collection<DownloadJobStatus> statuses);

    /**
     * Cuenta creaciones del navegador desde el instante incluido, independientemente del estado
     * final.
     *
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param createdAt Instante de creación del registro.
     * @return número de trabajos que cumplen los filtros.
     */
    long countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual(
            String anonymousOwnerHash, Instant createdAt);

    /**
     * Cuenta creaciones anónimas del hash de IP desde el instante incluido.
     *
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param createdAt Instante de creación del registro.
     * @return número de trabajos que cumplen los filtros.
     */
    long countByAnonymousIpHashAndCreatedAtGreaterThanEqual(
            String anonymousIpHash, Instant createdAt);

    /**
     * Cuenta todos los trabajos cuyo estado no está entre los excluidos.
     *
     * @param statuses Estados excluidos del recuento o incluidos en la consulta de vencimiento,
     *     según el contrato.
     * @return número de trabajos que cumplen los filtros.
     */
    long countByStatusNotIn(Collection<DownloadJobStatus> statuses);

    /**
     * Cuenta trabajos de la cuenta cuyo estado no está entre los excluidos.
     *
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     * @param statuses Estados excluidos del recuento o incluidos en la consulta de vencimiento,
     *     según el contrato.
     * @return número de trabajos que cumplen los filtros.
     */
    long countByOwnerIdAndStatusNotIn(UUID ownerId, Collection<DownloadJobStatus> statuses);
}

package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Define el contrato de {@code CatalogSourceProjectionRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
interface CatalogSourceProjectionRepository extends JpaRepository<CatalogSourceProjectionEntity, UUID> {
    /**
     * Busca el resultado solicitado mediante {@code
     * findFirstByAppIdAndTrustStatusOrderBySourceRef}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param trustStatus Valor de {@code trustStatus} utilizado por la operación.
     * @return Resultado producido por {@code findFirstByAppIdAndTrustStatusOrderBySourceRef}.
     */
    Optional<CatalogSourceProjectionEntity> findFirstByAppIdAndTrustStatusOrderBySourceRef(UUID appId, String trustStatus);
}

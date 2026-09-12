package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Consulta fuentes proyectadas por aplicación y nivel de confianza usando la persistencia JPA.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.infrastructure.persistence.CatalogSourceProjectionEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
interface CatalogSourceProjectionRepository extends JpaRepository<CatalogSourceProjectionEntity, UUID> {
    /**
     * Selecciona la primera fuente que coincide con aplicación y confianza, ordenando por su UUID.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param trustStatus Estado de confianza exigido a la proyección del catálogo.
     * @return proyección coincidente o vacío.
     */
    Optional<CatalogSourceProjectionEntity> findFirstByAppIdAndTrustStatusOrderBySourceRef(UUID appId, String trustStatus);
}

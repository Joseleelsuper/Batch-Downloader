package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Representa la proyección persistida de una fuente y su confianza para consultas del catálogo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.downloads.infrastructure.persistence.CatalogSourceProjectionRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Entity
@Table(name = "catalog_source_projections")
class CatalogSourceProjectionEntity {
    /**
     * UUID de la fuente.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_ref", length = 36, nullable = false)
    private UUID sourceRef;
    /**
     * UUID de la aplicación del catálogo.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "app_id", length = 36, nullable = false)
    private UUID appId;
    /**
     * Estado {@code trustStatus} mantenido por {@code CatalogSourceProjectionEntity}.
     */
    @Column(name = "trust_status", length = 24, nullable = false)
    private String trustStatus;

    /**
     * Permite a JPA reconstruir la proyección desde sus columnas persistidas.
     */
    protected CatalogSourceProjectionEntity() {}

    /**
     * Expone la identidad de la fuente proyectada.
     *
     * @return UUID de la fuente.
     */
    UUID sourceRef() { return sourceRef; }
    /**
     * Identifica la aplicación propietaria de la fuente.
     *
     * @return UUID de la aplicación del catálogo.
     */
    UUID appId() { return appId; }
}

package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Implementa el componente {@code CatalogSourceProjectionEntity}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Entity
@Table(name = "catalog_source_projections")
class CatalogSourceProjectionEntity {
    /**
     * Estado {@code sourceRef} mantenido por {@code CatalogSourceProjectionEntity}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_ref", length = 36, nullable = false)
    private UUID sourceRef;
    /**
     * Estado {@code appId} mantenido por {@code CatalogSourceProjectionEntity}.
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
     * Inicializa una instancia de {@code CatalogSourceProjectionEntity}.
     */
    protected CatalogSourceProjectionEntity() {}

    /**
     * Ejecuta la operación {@code sourceRef}.
     *
     * @return Resultado producido por {@code sourceRef}.
     */
    UUID sourceRef() { return sourceRef; }
    /**
     * Ejecuta la operación {@code appId}.
     *
     * @return Resultado producido por {@code appId}.
     */
    UUID appId() { return appId; }
}

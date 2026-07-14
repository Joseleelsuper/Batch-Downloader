package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "catalog_source_projections")
class CatalogSourceProjectionEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_ref", length = 36, nullable = false)
    private UUID sourceRef;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "app_id", length = 36, nullable = false)
    private UUID appId;
    @Column(name = "trust_status", length = 24, nullable = false)
    private String trustStatus;

    protected CatalogSourceProjectionEntity() {}

    UUID sourceRef() { return sourceRef; }
    UUID appId() { return appId; }
}

package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encapsula las mutaciones administrativas de fuentes de descarga.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class AdminAppSourceRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final Clock clock;

    /**
     * Inicializa el repositorio de fuentes.
     *
     * @param jdbc acceso JDBC
     * @param catalog resolución de aplicaciones públicas
     * @param clock reloj de aplicación
     */
    public AdminAppSourceRepository(JdbcTemplate jdbc, CatalogRepository catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Modifica una fuente únicamente dentro de su aplicación propietaria.
     *
     * @param appId identificador público de la aplicación
     * @param sourceId identificador UUID de la fuente
     * @param request campos modificables
     */
    @Transactional
    public void patch(String appId, String sourceId, PatchSourceRequest request) {
        UUID applicationId = catalog.softwareAppId(appId);
        UUID id = parseUuid(sourceId);
        int updated = jdbc.update(
                """
                UPDATE download_sources
                SET operating_system = COALESCE(?, operating_system),
                    architecture = COALESCE(?, architecture),
                    initial_url = COALESCE(?, initial_url),
                    resolver_type = COALESCE(?, resolver_type),
                    resolution_status = COALESCE(?, resolution_status),
                    validation_status = COALESCE(?, validation_status),
                    updated_at = ?,
                    version = version + 1
                WHERE id = ? AND software_app_id = ?
                """,
                blankToNull(request.operatingSystem()),
                blankToNull(request.architecture()),
                blankToNull(request.initialUrl()),
                blankToNull(request.resolverType()),
                blankToNull(request.resolutionStatus()),
                blankToNull(request.validationStatus()),
                LocalDateTime.now(clock),
                UuidBytes.fromUuid(id),
                UuidBytes.fromUuid(applicationId));
        if (updated == 0) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
        }
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

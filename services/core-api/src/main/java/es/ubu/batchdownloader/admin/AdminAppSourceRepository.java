package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminCatalogDtos.PatchSourceRequest;
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
 * Edita fuentes iniciales de descarga comprobando su pertenencia al catálogo; no publica ni valida
 * candidatos resueltos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppRepository
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración del catálogo
 */
@Repository
public class AdminAppSourceRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final Clock clock;

    /**
     * Conecta la edición de fuentes con la resolución de aplicaciones y el reloj de cambios.
     *
     * @param jdbc Acceso SQL que participa en la transacción administrativa del llamador.
     * @param catalog Consulta de proyecciones e identificadores internos del catálogo.
     * @param clock Reloj utilizado para fechar los cambios persistidos.
     */
    public AdminAppSourceRepository(JdbcTemplate jdbc, CatalogRepository catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    /**
     * Sustituye solo campos no blancos de la fuente, incrementa su versión y fecha el cambio si
     * pertenece a la aplicación.
     *
     * @param appId Identificador de la aplicación propietaria de los datos modificados.
     * @param sourceId UUID textual de la fuente inicial que debe pertenecer a la aplicación.
     * @param request Campos validados de la creación, edición o confirmación solicitada.
     * @throws es.ubu.batchdownloader.common.NotFoundException si la aplicación o el UUID de fuente
     *     no existen, o la fuente pertenece a otra aplicación.
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

    /**
     * Interpreta el identificador de fuente y traduce un formato UUID inválido al mismo error que
     * una fuente inexistente.
     *
     * @param raw UUID textual recibido para identificar la fuente.
     * @return UUID validado.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el texto no tiene formato UUID.
     */
    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
        }
    }

    /**
     * Marca los campos vacíos como ausentes para que COALESCE conserve el valor persistido.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return null si falta texto; en otro caso el valor original sin recortar.
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

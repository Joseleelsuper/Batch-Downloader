package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.CreateSoftwareRequest;
import es.ubu.batchdownloader.admin.AdminDtos.SoftwareRequestItem;
import es.ubu.batchdownloader.common.UuidBytes;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone las operaciones HTTP gestionadas por {@code SoftwareRequestController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
public class SoftwareRequestController {
    /**
     * Estado {@code jdbc} mantenido por {@code SoftwareRequestController}.
     */
    private final JdbcTemplate jdbc;

    /**
     * Inicializa una instancia de {@code SoftwareRequestController}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     */
    public SoftwareRequestController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Crea el recurso solicitado mediante {@code create}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code create}.
     */
    @PostMapping("/api/v1/software-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SoftwareRequestItem create(@Valid @RequestBody CreateSoftwareRequest request) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                INSERT INTO software_requests
                (id, requested_name, official_url, description, generated_description,
                 status, requester_email, created_at, updated_at)
                VALUES (?, ?, ?, ?, NULL, 'pending', ?, ?, ?)
                """,
                UuidBytes.fromUuid(id),
                request.requestedName(),
                request.officialUrl(),
                request.description(),
                request.requesterEmail(),
                now,
                now);
        return new SoftwareRequestItem(
                id.toString(),
                request.requestedName(),
                request.officialUrl(),
                request.description(),
                null,
                "pending",
                request.requesterEmail(),
                now,
                now);
    }

    /**
     * Enumera los elementos solicitados mediante {@code list}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    @GetMapping("/api/v1/admin/requests")
    public List<SoftwareRequestItem> list(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.query(
                """
                SELECT * FROM software_requests
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new SoftwareRequestItem(
                        UuidBytes.toUuid(rs.getBytes("id")).toString(),
                        rs.getString("requested_name"),
                        rs.getString("official_url"),
                        rs.getString("description"),
                        rs.getString("generated_description"),
                        rs.getString("status"),
                        rs.getString("requester_email"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                Math.max(1, Math.min(limit, 200)));
    }
}

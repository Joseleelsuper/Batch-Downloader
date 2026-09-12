package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.SoftwareRequestDtos.CreateSoftwareRequest;
import es.ubu.batchdownloader.admin.SoftwareRequestDtos.SoftwareRequestItem;
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
 * Recibe propuestas de aplicaciones en estado pendiente y permite a administración consultar las
 * más recientes para revisarlas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.SoftwareRequestDtos
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
@RestController
public class SoftwareRequestController {
    /**
     * Estado {@code jdbc} mantenido por {@code SoftwareRequestController}.
     */
    private final JdbcTemplate jdbc;

    /**
     * Conecta la persistencia de propuestas y su listado administrativo.
     *
     * @param jdbc Acceso SQL para guardar y consultar propuestas de software.
     */
    public SoftwareRequestController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Guarda una propuesta con UUID nuevo, estado pending y sin descripción generada, conservando
     * los datos de contacto opcionales.
     *
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @return 201 con la solicitud registrada.
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
     * Consulta propuestas por fecha de creación descendente con un límite acotado a 1–200.
     *
     * @param limit Máximo solicitado de registros; el repositorio aplica el límite propio de cada
     *     consulta.
     * @return solicitudes recientes para revisión administrativa.
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

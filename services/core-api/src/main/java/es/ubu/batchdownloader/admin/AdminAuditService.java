package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.common.UuidBytes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Coordina las operaciones de negocio de {@code AdminAuditService}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class AdminAuditService {
    /**
     * Estado {@code jdbcTemplate} mantenido por {@code AdminAuditService}.
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code AdminAuditService}.
     */
    private final ObjectMapper objectMapper;

    /**
     * Inicializa una instancia de {@code AdminAuditService}.
     *
     * @param jdbcTemplate Valor de {@code jdbcTemplate} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     */
    public AdminAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Ejecuta la operación {@code record}.
     *
     * @param actor Identidad del actor que solicita la operación.
     * @param action Valor de {@code action} utilizado por la operación.
     * @param targetType Valor de {@code targetType} utilizado por la operación.
     * @param targetId Identificador de {@code target} utilizado por la operación.
     * @param safeMetadata Valor de {@code safeMetadata} utilizado por la operación.
     */
    public void record(String actor, String action, String targetType, String targetId, Map<String, Object> safeMetadata) {
        try {
            jdbcTemplate.update(
                    """
                INSERT INTO admin_audit_logs
                (id, actor, action, target_type, target_id, safe_metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                    UuidBytes.fromUuid(UUID.randomUUID()),
                    actor,
                    action,
                    targetType,
                    targetId,
                    objectMapper.writeValueAsString(safeMetadata == null ? Map.of() : safeMetadata),
                    LocalDateTime.now());
        } catch (Exception ignored) {
            // La auditoría no debe filtrar datos sensibles ni interrumpir la operación del usuario.
        }
    }
}

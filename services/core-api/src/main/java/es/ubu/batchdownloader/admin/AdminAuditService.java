package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.common.UuidBytes;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persiste auditoría administrativa con metadatos previamente filtrados; registra y cuenta los
 * fallos sin impedir que se entregue el resultado de la operación principal.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppController
 * @see es.ubu.batchdownloader.admin.AdminScraperController
 * @see es.ubu.batchdownloader.admin.AdminSemanticController
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración del catálogo
 */
@Service
public class AdminAuditService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditService.class);
    /**
     * Estado {@code jdbcTemplate} mantenido por {@code AdminAuditService}.
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code AdminAuditService}.
     */
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter failures;

    /**
     * Conecta persistencia y serialización y registra el contador admin.audit.failures.
     *
     * @param jdbcTemplate Acceso SQL utilizado para persistir la auditoría administrativa.
     * @param objectMapper Serializador de los campos seguros de auditoría.
     * @param clock Reloj utilizado para fechar los cambios persistidos.
     * @param meterRegistry Registro del contador de fallos de persistencia de auditoría.
     */
    public AdminAuditService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.failures = Counter.builder("admin.audit.failures")
                .description("Operaciones administrativas cuya auditoría no pudo persistirse")
                .register(meterRegistry);
    }

    /**
     * Guarda actor, acción, destino y metadatos con fecha UTC. Si fallan la serialización o la
     * inserción, incrementa el contador y registra solo acción, tipo de destino y clase de fallo.
     *
     * @param actor UUID textual del administrador que confirma la evidencia.
     * @param action Acción administrativa realizada, sin datos secretos.
     * @param targetType Tipo del recurso sobre el que se realizó la acción.
     * @param targetId Identificador del recurso afectado.
     * @param safeMetadata Datos ya filtrados por el llamador; null se guarda como un objeto vacío.
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
                    LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (Exception exception) {
            failures.increment();
            LOGGER.warn(
                    "No se pudo persistir la auditoría administrativa action={} targetType={} failureType={}",
                    action,
                    targetType,
                    exception.getClass().getSimpleName());
        }
    }
}

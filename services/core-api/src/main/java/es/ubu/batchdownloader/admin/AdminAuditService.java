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
 * Coordina las operaciones de negocio de {@code AdminAuditService}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Inicializa una instancia de {@code AdminAuditService}.
     *
     * @param jdbcTemplate Valor de {@code jdbcTemplate} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param clock Reloj determinista utilizado para fechar el evento.
     * @param meterRegistry Registro de métricas operativas.
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

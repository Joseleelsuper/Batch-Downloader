package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.common.UuidBytes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

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
            // Auditing must not leak sensitive payloads or break the user operation in the MVP.
        }
    }
}

package es.ubu.batchdownloader.admin;

import java.time.LocalDateTime;

/**
 * Agrupa los contratos de auditoría con actor UUID y metadatos seguros.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class AdminAuditDtos {
    /**
     * Impide instanciar el contenedor de contratos de auditoría con actor UUID y metadatos seguros.
     */
    private AdminAuditDtos() {}

    /**
     * Describe quién realizó una acción administrativa, qué recurso afectó y qué metadatos seguros
     * se conservaron.
     *
     * @param actor UUID textual de la cuenta administrativa que solicitó la operación.
     * @param action Nombre estable de la acción administrativa registrada.
     * @param targetType Tipo funcional del recurso afectado por la acción.
     * @param targetId UUID o identificador del recurso afectado, sin credenciales.
     * @param safeMetadata JSON de diagnóstico o auditoría limitado a campos seguros.
     * @param createdAt Fecha de creación del registro, inspección o solicitud.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record AdminAuditItem(
            String actor,
            String action,
            String targetType,
            String targetId,
            String safeMetadata,
            LocalDateTime createdAt) {}

}

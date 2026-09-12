package es.ubu.batchdownloader.admin;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * Agrupa los contratos de propuestas de software pendientes de revisión.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.SoftwareRequestController
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class SoftwareRequestDtos {
    /**
     * Impide instanciar el contenedor de contratos de propuestas de software pendientes de
     * revisión.
     */
    private SoftwareRequestDtos() {}

    /**
     * Presenta una propuesta de software, su contacto opcional y el estado de revisión con fechas
     * de seguimiento.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param requestedName Nombre de la aplicación propuesta para incorporar al catálogo.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param description Descripción breve del propósito de la aplicación.
     * @param generatedDescription Descripción generada posteriormente; null al crear la solicitud.
     * @param status Estado de revisión de la propuesta; pending al crearla.
     * @param requesterEmail Correo opcional de contacto de quien propone el software.
     * @param createdAt Fecha de creación del registro, inspección o solicitud.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record SoftwareRequestItem(
            String id,
            String requestedName,
            String officialUrl,
            String description,
            String generatedDescription,
            String status,
            String requesterEmail,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /**
     * Exige nombre y página oficial de una propuesta de software y permite aportar descripción y
     * contacto opcionales.
     *
     * @param requestedName Nombre de la aplicación propuesta para incorporar al catálogo.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param description Descripción breve del propósito de la aplicación.
     * @param requesterEmail Correo opcional de contacto de quien propone el software.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record CreateSoftwareRequest(
            @NotBlank String requestedName,
            @NotBlank String officialUrl,
            String description,
            String requesterEmail) {}

}

package es.ubu.batchdownloader.admin;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Agrupa los contratos de alta y edición administrativa de aplicaciones y fuentes.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppController
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class AdminCatalogDtos {
    /**
     * Impide instanciar el contenedor de contratos de alta y edición administrativa de aplicaciones
     * y fuentes.
     */
    private AdminCatalogDtos() {}

    /**
     * Transporta los metadatos de un alta administrativa; el repositorio deriva identificador
     * manual y slug cuando faltan.
     *
     * @param winstallId Identificador Winstall o clave manual.* para aplicaciones incorporadas
     *     manualmente.
     * @param slug Identificador legible de la aplicación en la ruta de detalle.
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param appStatus Estado de publicación active o inactive, distinto de catalogStatus.
     * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir
     *     mayúsculas.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record UpsertAppRequest(
            String winstallId,
            String slug,
            @NotBlank String name,
            String publisher,
            String description,
            String longDescription,
            String iconUrl,
            String officialUrl,
            String latestVersion,
            String appStatus,
            List<String> tags) {}

    /**
     * Describe cambios opcionales de metadatos; cada campo null conserva su valor anterior.
     *
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param appStatus Estado de publicación active o inactive, distinto de catalogStatus.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record PatchAppRequest(
            String name,
            String publisher,
            String description,
            String longDescription,
            String iconUrl,
            String officialUrl,
            String latestVersion,
            String appStatus) {}

    /**
     * Transporta el conjunto completo de etiquetas que reemplaza al anterior en una aplicación.
     *
     * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir
     *     mayúsculas.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ReplaceTagsRequest(List<String> tags) {}

    /**
     * Describe cambios opcionales de una fuente, aplicables únicamente si pertenece a la aplicación
     * de la ruta.
     *
     * @param operatingSystem Plataforma concreta del candidato: windows, linux o macos.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @param initialUrl Página inicial propuesta para la fuente; nunca representa una URL final ya
     *     validada.
     * @param resolverType Proveedor o estrategia de resolución de esa fuente.
     * @param resolutionStatus Resultado de resolución de la fuente: directa, fallback, revisión o
     *     ausencia.
     * @param validationStatus Resultado de validación del instalador; unchecked representa falta de
     *     validación vigente en la vista.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record PatchSourceRequest(
            String operatingSystem,
            String architecture,
            String initialUrl,
            String resolverType,
            String resolutionStatus,
            String validationStatus) {}

}

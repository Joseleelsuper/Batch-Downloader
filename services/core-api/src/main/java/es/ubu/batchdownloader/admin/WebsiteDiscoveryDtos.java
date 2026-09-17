package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerSuggestions;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerAiState;

/**
 * Agrupa los contratos de descubrimiento y publicación desde páginas oficiales.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.ScraperInternalClient
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class WebsiteDiscoveryDtos {
    /**
     * Impide instanciar el contenedor de contratos de descubrimiento y publicación desde páginas
     * oficiales.
     */
    private WebsiteDiscoveryDtos() {}

    /**
     * Solicita inspeccionar una web oficial HTTPS y permite fijar enlaces HTTPS de instaladores por
     * plataforma.
     *
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param installerUrls URLs opcionales por plataforma; ausencia se normaliza a una estructura
     *     sin enlaces.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppDiscoveryRequest(
            @NotBlank
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String officialUrl,
            @Valid WebsiteAppInstallerUrls installerUrls) {
        /**
         * Normaliza la ausencia de enlaces explícitos a una estructura con tres plataformas sin
         * URL.
         *
         * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
         * @param installerUrls URLs opcionales por plataforma; ausencia se normaliza a una
         *     estructura sin enlaces.
         */
        public WebsiteAppDiscoveryRequest {
            if (installerUrls == null) {
                installerUrls = new WebsiteAppInstallerUrls(null, null, null);
            }
        }
    }

    /**
     * Agrupa enlaces HTTPS opcionales por plataforma sin sustituir una selección exacta por otra
     * automática.
     *
     * @param windows URL HTTPS opcional del instalador Windows, de hasta 2048 caracteres.
     * @param macos URL HTTPS opcional del instalador macOS, de hasta 2048 caracteres.
     * @param linux URL HTTPS opcional del instalador Linux, de hasta 2048 caracteres.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppInstallerUrls(
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String windows,
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String macos,
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String linux) {}

    /**
     * Identifica un candidato descubierto y sus metadatos técnicos observados sin exponer la URL
     * final.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param finalDomain Dominio final observado, sin revelar la URL resuelta completa.
     * @param filename Nombre conocido del instalador o null si no hay una fuente resuelta
     *     utilizable.
     * @param extension Extensión del instalador con punto inicial, o null si no se conoce.
     * @param contentType Tipo MIME observado para el instalador cuando está disponible.
     * @param sizeBytes Tamaño conocido del instalador en bytes, o null si no existe medición.
     * @param version Versión del instalador, o token de cambio cuando el contrato describe un
     *     evento de catálogo.
     * @param operatingSystem Plataforma concreta del candidato: windows, linux o macos.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppDiscoveryInstaller(
            String id,
            String finalDomain,
            String filename,
            String extension,
            String contentType,
            Long sizeBytes,
            String version,
            String operatingSystem,
            String architecture) {}

    /**
     * Conserva progreso, enlaces aportados, candidatos y sugerencias de un descubrimiento
     * recuperable hasta su aplicación o vencimiento.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param status Estado persistido del descubrimiento.
     * @param phase Etapa actual del flujo persistente de inspección, descubrimiento o resolución.
     * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
     *     después de aplicar.
     * @param providedInstallerPlatforms Plataformas para las que el administrador aportó URLs
     *     explícitas.
     * @param suggestions Valores propuestos con su procedencia; no sustituyen decisiones
     *     administrativas sin aplicar el flujo.
     * @param installers Candidatos inspeccionados o descubiertos en el orden de la respuesta.
     * @param ai Estado de generación de sugerencias y proveedor o modelo, sin prompts ni
     *     credenciales.
     * @param errorCode Código seguro del fallo persistido, o null si no hay un error que comunicar.
     * @param appliedAppId UUID de la aplicación publicada, o null mientras el descubrimiento no se
     *     ha aplicado.
     * @param createdAt Fecha de creación del registro, inspección o solicitud.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @param expiresAt Límite de vigencia del descubrimiento antes de exigir otra inspección.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppDiscovery(
            String id,
            String status,
            String phase,
            List<String> warnings,
            List<String> providedInstallerPlatforms,
            ManualInstallerSuggestions suggestions,
            List<WebsiteAppDiscoveryInstaller> installers,
            ManualInstallerAiState ai,
            String errorCode,
            String appliedAppId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime expiresAt) {}

    /**
     * Transporta los metadatos seleccionados por administración para publicar una aplicación desde
     * su web oficial inspeccionada.
     *
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppDiscoveryApplyRequest(
            @NotBlank @Size(max = 180) String name,
            @Size(max = 180) String publisher,
            @NotBlank
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String officialUrl,
            @Size(max = 100) String latestVersion,
            @Size(max = 4000) String description,
            @Size(max = 12000) String longDescription,
            @Size(max = 2048) String iconUrl) {}

    /**
     * Resume identidad, versión y estado de aplicación e instaladores que Scraper acaba de
     * publicar.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @param appVersion Versión persistida de la aplicación que acompaña a la evidencia o resultado
     *     de publicación.
     * @param catalogStatus Proyección pública available, review o missing que determina la
     *     disponibilidad efectiva.
     * @param installerCount Número de instaladores publicados por el descubrimiento.
     * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
     *     después de aplicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppDiscoveryApplyResult(
            String appId,
            long appVersion,
            String catalogStatus,
            int installerCount,
            List<String> warnings) {}

    /**
     * Entrega el detalle público actualizado, la cantidad publicada y las advertencias del
     * descubrimiento aplicado.
     *
     * @param application Detalle público de la aplicación consultado después de aplicar la
     *     publicación.
     * @param installerCount Número de instaladores publicados por el descubrimiento.
     * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
     *     después de aplicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record WebsiteAppDiscoveryApplyResponse(
            AppDetails application,
            int installerCount,
            List<String> warnings) {}

}

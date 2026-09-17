package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppInstallerUrls;

/**
 * Agrupa los contratos de inspección y publicación de instaladores propuestos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.ScraperInternalClient
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
public final class InstallerInspectionDtos {
    /**
     * Impide instanciar el contenedor de contratos de inspección y publicación de instaladores
     * propuestos.
     */
    private InstallerInspectionDtos() {}

    /**
     * Permite uno o varios enlaces HTTPS por plataforma y exige una página de procedencia; la
     * validación cruzada requiere al menos un instalador.
     *
     * @param installerUrl URL HTTPS opcional de un instalador, de hasta 2048 caracteres.
     * @param installerUrls URLs opcionales por plataforma; ausencia se normaliza a una estructura
     *     sin enlaces.
     * @param sourcePageUrl Página HTTPS de procedencia obligatoria, de hasta 2048 caracteres.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerInspectionRequest(
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String installerUrl,
            @Valid WebsiteAppInstallerUrls installerUrls,
            @NotBlank
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String sourcePageUrl) {
        /**
         * Sustituye las URLs por plataforma ausentes por una estructura con tres valores null para
         * simplificar la validación cruzada.
         *
         * @param installerUrl URL HTTPS opcional de un instalador, de hasta 2048 caracteres.
         * @param installerUrls URLs opcionales por plataforma; ausencia se normaliza a una
         *     estructura sin enlaces.
         * @param sourcePageUrl Página HTTPS de procedencia obligatoria, de hasta 2048 caracteres.
         */
        public ManualInstallerInspectionRequest {
            if (installerUrls == null) {
                installerUrls = new WebsiteAppInstallerUrls(null, null, null);
            }
        }

        /**
         * Acepta un enlace general o al menos uno de Windows, macOS o Linux no vacío.
         *
         * @return true si existe un instalador propuesto.
         */
        @AssertTrue(message = "Debe indicarse al menos una URI de instalador.")
        public boolean hasInstallerUrl() {
            return hasText(installerUrl)
                    || hasText(installerUrls.windows())
                    || hasText(installerUrls.macos())
                    || hasText(installerUrls.linux());
        }

        /**
         * Distingue una URL aportada de un valor null o compuesto solo por espacios.
         *
         * @param value Texto que se normaliza o clasifica según el método.
         * @return true si el texto está presente y no es blanco.
         */
        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * Asocia una propuesta de metadato con su origen para que el administrador evalúe de dónde
     * procede.
     *
     * @param value Texto propuesto para el campo; no se guarda hasta aplicar la selección.
     * @param source Origen del dato sugerido que permite al administrador evaluar su procedencia.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualFieldSuggestion(String value, String source) {}

    /**
     * Agrupa nombre, editor, versiones, descripciones e imágenes propuestas con procedencia
     * individual para su revisión.
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
    public record ManualInstallerSuggestions(
            ManualFieldSuggestion name,
            ManualFieldSuggestion publisher,
            ManualFieldSuggestion officialUrl,
            ManualFieldSuggestion latestVersion,
            ManualFieldSuggestion description,
            ManualFieldSuggestion longDescription,
            ManualFieldSuggestion iconUrl) {}

    /**
     * Expone formato, tamaño y plataforma observados del instalador sin revelar su dirección final
     * protegida.
     *
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
     * @param platformRequired El formato inspeccionado no basta para decidir plataforma y requiere
     *     una selección explícita.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerTechnicalData(
            String finalDomain,
            String filename,
            String extension,
            String contentType,
            Long sizeBytes,
            String version,
            String operatingSystem,
            String architecture,
            boolean platformRequired) {}

    /**
     * Resume el estado de generación de sugerencias y su proveedor y modelo sin transportar prompts
     * ni respuestas completas.
     *
     * @param status Estado de la fase de generación de sugerencias.
     * @param provider Proveedor de generación que produjo sugerencias, sin incluir su configuración
     *     secreta.
     * @param model Modelo utilizado para generar sugerencias cuando se conoce.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerAiState(
            String status,
            String provider,
            String model) {}

    /**
     * Conserva progreso, versión esperada, candidatos y diagnóstico de una inspección que puede
     * recuperarse y aplicarse posteriormente.
     *
     * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @param status Estado persistido de la inspección.
     * @param phase Etapa actual del flujo persistente de inspección, descubrimiento o resolución.
     * @param expectedAppVersion Versión no negativa leída al iniciar la inspección o editar; la
     *     publicación exige que siga vigente.
     * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
     *     después de aplicar.
     * @param suggestions Valores propuestos con su procedencia; no sustituyen decisiones
     *     administrativas sin aplicar el flujo.
     * @param installer Candidato único conservado por compatibilidad con respuestas anteriores.
     * @param installers Candidatos inspeccionados o descubiertos en el orden de la respuesta.
     * @param ai Estado de generación de sugerencias y proveedor o modelo, sin prompts ni
     *     credenciales.
     * @param errorCode Código seguro del fallo persistido, o null si no hay un error que comunicar.
     * @param sourceRef UUID de la fuente principal publicada; null mientras no se ha aplicado el
     *     flujo.
     * @param createdAt Fecha de creación del registro, inspección o solicitud.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @param expiresAt Límite de vigencia de la inspección antes de exigir otra comprobación.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerInspection(
            String id,
            String appId,
            String status,
            String phase,
            long expectedAppVersion,
            List<String> warnings,
            ManualInstallerSuggestions suggestions,
            ManualInstallerTechnicalData installer,
            List<ManualInstallerTechnicalData> installers,
            ManualInstallerAiState ai,
            String errorCode,
            String sourceRef,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime expiresAt) {
        /**
         * Copia advertencias y candidatos; si falta la lista de instaladores reconstruye el
         * contrato múltiple desde el candidato único anterior.
         *
         * @param id UUID estable del registro, inspección, ejecución o propuesta representada.
         * @param appId UUID de la aplicación; las rutas textuales también admiten slug o
         *     identificador Winstall.
         * @param status Estado persistido del flujo o registro descrito, distinto del estado
         *     público del catálogo.
         * @param phase Etapa actual del flujo persistente de inspección, descubrimiento o
         *     resolución.
         * @param expectedAppVersion Versión no negativa leída al iniciar la inspección o editar; la
         *     publicación exige que siga vigente.
         * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
         *     después de aplicar.
         * @param suggestions Valores propuestos con su procedencia; no sustituyen decisiones
         *     administrativas sin aplicar el flujo.
         * @param installer Candidato único conservado por compatibilidad con respuestas anteriores.
         * @param installers Candidatos inspeccionados o descubiertos en el orden de la respuesta.
         * @param ai Estado de generación de sugerencias y proveedor o modelo, sin prompts ni
         *     credenciales.
         * @param errorCode Código seguro del fallo persistido, o null si no hay un error que
         *     comunicar.
         * @param sourceRef UUID de la fuente principal publicada; null mientras no se ha aplicado
         *     el flujo.
         * @param createdAt Fecha de creación del registro, inspección o solicitud.
         * @param updatedAt Fecha de la última actualización de la aplicación.
         * @param expiresAt Límite de vigencia de la inspección antes de exigir otra comprobación.
         */
        public ManualInstallerInspection {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
                        List<ManualInstallerTechnicalData> normalizedInstallers;
                        if (installers == null) {
                                normalizedInstallers = installer == null ? List.of() : List.of(installer);
                        } else {
                                normalizedInstallers = List.copyOf(installers);
                        }
                        installers = normalizedInstallers;
        }
    }

    /**
     * Transporta los metadatos elegidos y la versión esperada de aplicación para publicar una
     * inspección sin sobrescribir cambios concurrentes.
     *
     * @param expectedAppVersion Versión no negativa leída al iniciar la inspección o editar; la
     *     publicación exige que siga vigente.
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @param operatingSystem Plataforma concreta del candidato: windows, linux o macos.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerApplyRequest(
            @NotNull @PositiveOrZero Long expectedAppVersion,
            @NotBlank @Size(max = 180) String name,
            @Size(max = 180) String publisher,
            @Size(max = 2048) String officialUrl,
            @Size(max = 100) String latestVersion,
            @Size(max = 4000) String description,
            @Size(max = 12000) String longDescription,
            @Size(max = 2048) String iconUrl,
            @Pattern(regexp = "^(windows|macos|linux)$") String operatingSystem) {}

    /**
     * Conserva UUID de aplicación, fuentes exactas, versión y estado que devuelve Scraper al
     * publicar una inspección.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @param sourceRef UUID de la fuente principal publicada; null mientras no se ha aplicado el
     *     flujo.
     * @param sourceRefs UUID exactos de todas las fuentes publicadas, conservando el orden del
     *     resultado.
     * @param appVersion Versión persistida de la aplicación que acompaña a la evidencia o resultado
     *     de publicación.
     * @param catalogStatus Proyección pública available, review o missing que determina la
     *     disponibilidad efectiva.
     * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
     *     después de aplicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerApplyResult(
            String appId,
            String sourceRef,
            List<String> sourceRefs,
            long appVersion,
            String catalogStatus,
            List<String> warnings) {
        /**
         * Copia listas y reconstruye sourceRefs desde la referencia única cuando un productor del
         * contrato anterior omite la lista.
         *
         * @param appId UUID de la aplicación; las rutas textuales también admiten slug o
         *     identificador Winstall.
         * @param sourceRef UUID de la fuente principal publicada; null mientras no se ha aplicado
         *     el flujo.
         * @param sourceRefs UUID exactos de todas las fuentes publicadas, conservando el orden del
         *     resultado.
         * @param appVersion Versión persistida de la aplicación que acompaña a la evidencia o
         *     resultado de publicación.
         * @param catalogStatus Proyección pública available, review o missing que determina la
         *     disponibilidad efectiva.
         * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
         *     después de aplicar.
         */
        public ManualInstallerApplyResult {
            if (sourceRefs == null) {
                sourceRefs = sourceRef == null ? List.of() : List.of(sourceRef);
            } else {
                sourceRefs = List.copyOf(sourceRefs);
            }
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Combina el detalle público actualizado con fuentes exactas y advertencias de la publicación.
     *
     * @param application Detalle público de la aplicación consultado después de aplicar la
     *     publicación.
     * @param sourceRef UUID de la fuente principal publicada; null mientras no se ha aplicado el
     *     flujo.
     * @param sourceRefs UUID exactos de todas las fuentes publicadas, conservando el orden del
     *     resultado.
     * @param warnings Advertencias seguras del flujo que el administrador puede revisar antes o
     *     después de aplicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración
     */
    public record ManualInstallerApplyResponse(
            AppDetails application,
            String sourceRef,
            List<String> sourceRefs,
            List<String> warnings) {}

}

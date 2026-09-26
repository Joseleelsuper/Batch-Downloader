package es.ubu.batchdownloader.catalog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agrupa proyecciones públicas del catálogo y sus metadatos de búsqueda, fuente seleccionable y
 * actualización.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogController
 * @see es.ubu.batchdownloader.catalog.CatalogProjectionRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
public class CatalogDtos {
    /**
     * Resume una aplicación activa para tarjetas de búsqueda y bundles sin transportar URLs de
     * instaladores.
     *
     * @param id UUID público de la aplicación o de la fuente exacta en una opción de descarga.
     * @param slug Identificador legible de la aplicación en la ruta de detalle.
     * @param packageId Identificador Winstall o manual de la aplicación, distinto de su UUID
     *     público.
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir
     *     mayúsculas.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param sourceLabel Etiqueta visible que distingue sitio oficial, fallback, revisión o
     *     ausencia.
     * @param resolutionStatus Resultado de resolución de la fuente: directa, fallback, revisión o
     *     ausencia.
     * @param validationStatus Resultado de validación del instalador; unchecked representa falta de
     *     validación vigente en la vista.
     * @param downloadable Indica que la vista dispone de una fuente resuelta válida y
     *     seleccionable.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record AppListItem(
            String id,
            String slug,
            String packageId,
            String name,
            String publisher,
            String description,
            String longDescription,
            List<String> tags,
            List<String> operatingSystems,
            String iconUrl,
            String latestVersion,
            String sourceLabel,
            String resolutionStatus,
            String validationStatus,
            boolean downloadable,
            LocalDateTime updatedAt) {}

    /**
     * Entrega página, total e índice alfabético junto al modo solicitado, el aplicado y el
     * diagnóstico seguro de degradación.
     *
     * @param data Aplicaciones de la página en el orden de búsqueda aplicado.
     * @param page Página numerada desde uno; el controlador la limita a un mínimo de uno.
     * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
     * @param total Número de aplicaciones que cumplen el conjunto completo de filtros antes de
     *     paginar.
     * @param alphabet Posiciones iniciales y recuentos por letra de la misma búsqueda ordenada por
     *     nombre.
     * @param requestedMode Modo de búsqueda solicitado por el cliente.
     * @param appliedMode Modo realmente aplicado a resultados, total y facetas.
     * @param modelVersion Modelo de embeddings usado; null cuando se aplica búsqueda léxica.
     * @param indexVersion Versión del índice semántico usado; null cuando se aplica búsqueda
     *     léxica.
     * @param degradedReason Código seguro de degradación a léxica o null si no hubo fallo que
     *     comunicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record AppSearchResponse(
            List<AppListItem> data,
            int page,
            int pageSize,
            long total,
            List<CatalogAlphabetEntry> alphabet,
            String requestedMode,
            String appliedMode,
            String modelVersion,
            String indexVersion,
            String degradedReason) {
        /**
         * Construye una página léxica sin índice alfabético ni metadatos semánticos para
         * consumidores del contrato abreviado.
         *
         * @param data Aplicaciones de la página en el orden de búsqueda aplicado.
         * @param page Página numerada desde uno; el controlador la limita a un mínimo de uno.
         * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
         * @param total Número de aplicaciones que cumplen el conjunto completo de filtros antes de
         *     paginar.
         */
        public AppSearchResponse(List<AppListItem> data, int page, int pageSize, long total) {
            this(data, page, pageSize, total, List.of(), "lexical", "lexical", null, null, null);
        }
    }

    /**
     * Relaciona un grupo alfabético con su primera página y el número de aplicaciones del mismo
     * conjunto filtrado.
     *
     * @param letter Grupo alfabético A–Z o # para prefijos no latinos o numéricos.
     * @param page Página numerada desde uno; el controlador la limita a un mínimo de uno.
     * @param count Número de aplicaciones distintas que pertenecen a la faceta o grupo alfabético.
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record CatalogAlphabetEntry(String letter, int page, long count) {}

    /**
     * Conserva etiqueta visible, valor de filtro, clave normalizada, grupo alfabético y recuento de
     * una faceta.
     *
     * @param label Texto visible de una faceta; se usa guion cuando falta o está en blanco.
     * @param value Texto visible que el cliente devuelve para aplicar esta faceta.
     * @param normalizedValue Clave de comparación de la faceta; si falta se deriva de su etiqueta
     *     visible.
     * @param letter Grupo alfabético A–Z o # para prefijos no latinos o numéricos.
     * @param count Número de aplicaciones distintas que pertenecen a la faceta o grupo alfabético.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record FacetItem(
            String label,
            String value,
            String normalizedValue,
            String letter,
            long count) {}

    /**
     * Entrega etiquetas y editores contados bajo los mismos filtros y modo de búsqueda.
     *
     * @param tags Facetas de etiquetas con recuentos y grupos alfabéticos.
     * @param publishers Facetas de editores con recuentos y grupos alfabéticos.
     * @param requestedMode Modo de búsqueda solicitado por el cliente.
     * @param appliedMode Modo realmente aplicado a resultados, total y facetas.
     * @param modelVersion Modelo de embeddings usado; null cuando se aplica búsqueda léxica.
     * @param indexVersion Versión del índice semántico usado; null cuando se aplica búsqueda
     *     léxica.
     * @param degradedReason Código seguro de degradación a léxica o null si no hubo fallo que
     *     comunicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record CatalogFacetsResponse(
            List<FacetItem> tags,
            List<FacetItem> publishers,
            String requestedMode,
            String appliedMode,
            String modelVersion,
            String indexVersion,
            String degradedReason) {
        /**
         * Construye facetas léxicas sin metadatos de un modelo semántico.
         *
         * @param tags Facetas de etiquetas.
         * @param publishers Facetas de editores.
         */
        public CatalogFacetsResponse(List<FacetItem> tags, List<FacetItem> publishers) {
            this(tags, publishers, "lexical", "lexical", null, null, null);
        }
    }

    /**
     * Representa un instalador por su UUID exacto y compatibilidad para que la selección del
     * usuario llegue intacta al worker.
     *
     * @param id UUID público de la aplicación o de la fuente exacta en una opción de descarga.
     * @param filename Nombre conocido del instalador o null si no hay una fuente resuelta
     *     utilizable.
     * @param extension Extensión del instalador con punto inicial, o null si no se conoce.
     * @param operatingSystem Plataforma concreta del candidato: windows, linux o macos.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @param version Versión del instalador, o token de cambio cuando el contrato describe un
     *     evento de catálogo.
     * @param isLatest El candidato corresponde a la versión más reciente reconocida.
     * @param versionStatus Estado de correspondencia de la versión con el catálogo.
     * @param sourceLabel Etiqueta visible que distingue sitio oficial, fallback, revisión o
     *     ausencia.
     * @param score Puntuación de preferencia del candidato según el resolvedor.
     * @param finalDomain Dominio final observado, sin revelar la URL resuelta completa.
     * @param isPrimary Se presenta como primera opción según el orden de selección de candidatos.
     * @param installationSupport automatic, manual o not_applicable según plataforma, extensión y
     *     perfil aprobado.
     * @param compatibleLinuxTargets Gestores Linux compatibles con el formato del instalador.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record DownloadOption(
            String id,
            String filename,
            String extension,
            String operatingSystem,
            String architecture,
            String version,
            boolean isLatest,
            String versionStatus,
            String sourceLabel,
            int score,
            String finalDomain,
            boolean isPrimary,
            String installationSupport,
            List<String> compatibleLinuxTargets) {
        /**
         * Calcula soporte y destinos Linux solo por formato cuando no se aporta un perfil aprobado.
         *
         * @param id UUID público de la aplicación o de la fuente exacta en una opción de descarga.
         * @param filename Nombre conocido del instalador o null si no hay una fuente resuelta
         *     utilizable.
         * @param extension Extensión del instalador con punto inicial, o null si no se conoce.
         * @param operatingSystem Plataforma concreta del candidato: windows, linux o macos.
         * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
         *     aplicación.
         * @param version Versión del instalador, o token de cambio cuando el contrato describe un
         *     evento de catálogo.
         * @param isLatest El candidato corresponde a la versión más reciente reconocida.
         * @param versionStatus Estado de correspondencia de la versión con el catálogo.
         * @param sourceLabel Etiqueta visible que distingue sitio oficial, fallback, revisión o
         *     ausencia.
         * @param score Puntuación de preferencia del candidato según el resolvedor.
         * @param finalDomain Dominio final observado, sin revelar la URL resuelta completa.
         * @param isPrimary Se presenta como primera opción según el orden de selección de
         *     candidatos.
         */
        public DownloadOption(String id, String filename, String extension, String operatingSystem,
                String architecture, String version, boolean isLatest, String versionStatus,
                String sourceLabel, int score, String finalDomain, boolean isPrimary) {
            this(id, filename, extension, operatingSystem, architecture, version, isLatest, versionStatus,
                    sourceLabel, score, finalDomain, isPrimary,
                    LinuxInstallationSupport.support(operatingSystem, extension, null),
                    "linux".equals(operatingSystem) ? LinuxInstallationSupport.targets(extension) : List.of());
        }
    }

    /**
     * Entrega descripción ampliada, procedencia pública y fuentes exactas de una aplicación sin
     * exponer direcciones finales protegidas.
     *
     * @param id UUID público de la aplicación o de la fuente exacta en una opción de descarga.
     * @param slug Identificador legible de la aplicación en la ruta de detalle.
     * @param packageId Identificador Winstall o manual de la aplicación, distinto de su UUID
     *     público.
     * @param name Nombre visible de la aplicación.
     * @param publisher Editor singular opcional; no se descompone por comas.
     * @param description Descripción breve del propósito de la aplicación.
     * @param longDescription Descripción ampliada que se muestra en el detalle y participa en la
     *     búsqueda léxica.
     * @param tags Etiquetas que debe cumplir conjuntamente cada aplicación, sin distinguir
     *     mayúsculas.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param iconUrl URL del icono público de la aplicación cuando está disponible.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param originUrl Página de procedencia que puede mostrarse al usuario, sin revelar la URL
     *     final protegida.
     * @param latestVersion Versión vigente de la aplicación según el catálogo.
     * @param installerFilename Nombre de la fuente principal del detalle, si existe.
     * @param installerType Extensión principal sin puntos y en mayúsculas, si existe.
     * @param contentType Tipo MIME observado para el instalador cuando está disponible.
     * @param sizeBytes Tamaño conocido del instalador en bytes, o null si no existe medición.
     * @param finalDomain Dominio final observado, sin revelar la URL resuelta completa.
     * @param score Puntuación de preferencia del candidato según el resolvedor.
     * @param resolutionStatus Resultado de resolución de la fuente: directa, fallback, revisión o
     *     ausencia.
     * @param validationStatus Resultado de validación del instalador; unchecked representa falta de
     *     validación vigente en la vista.
     * @param downloadable Indica que la vista dispone de una fuente resuelta válida y
     *     seleccionable.
     * @param updatedAt Fecha de la última actualización de la aplicación.
     * @param sourceLabel Etiqueta visible que distingue sitio oficial, fallback, revisión o
     *     ausencia.
     * @param checkedAt Fecha de la última comprobación de la fuente, o null si no existe.
     * @param expiresAt Fecha que activa revalidación de la fuente; por sí sola no retira un
     *     candidato válido del catálogo.
     * @param downloadOptions Hasta cincuenta fuentes seleccionables con identidad exacta y orden de
     *     preferencia.
     * @param notes Explicación visible de procedencia o necesidad de revisión del instalador.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record AppDetails(
            String id,
            String slug,
            String packageId,
            String name,
            String publisher,
            String description,
            String longDescription,
            List<String> tags,
            List<String> operatingSystems,
            String iconUrl,
            String officialUrl,
            String originUrl,
            String latestVersion,
            String installerFilename,
            String installerType,
            String contentType,
            Long sizeBytes,
            String finalDomain,
            Integer score,
            String resolutionStatus,
            String validationStatus,
            boolean downloadable,
            LocalDateTime updatedAt,
            String sourceLabel,
            LocalDateTime checkedAt,
            LocalDateTime expiresAt,
            List<DownloadOption> downloadOptions,
            String notes) {}

    /**
     * Resume fechas, progreso y fase de la última ejecución persistida del scraper.
     *
     * @param status Estado de ejecución del scraper; es distinto del estado público de una
     *     aplicación.
     * @param startedAt Fecha de inicio de la última ejecución del scraper.
     * @param heartbeatAt Fecha de su último latido persistido.
     * @param finishedAt Fecha de finalización o null si la ejecución todavía no ha terminado.
     * @param appsDiscovered Aplicaciones descubiertas durante la ejecución.
     * @param appsResolved Aplicaciones resueltas durante la ejecución.
     * @param appsFailed Aplicaciones cuyo procesamiento terminó con error.
     * @param appsSkipped Aplicaciones omitidas por las reglas de la ejecución.
     * @param currentPackageId Paquete que se está procesando, o null si no hay uno activo.
     * @param currentAppName Nombre visible de la aplicación actualmente procesada.
     * @param currentPhase Fase actual del pipeline de scraping.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    /**
     * Entrega contadores públicos por estado y la fecha UTC de generación.
     *
     * @param total Número de aplicaciones que cumplen el conjunto completo de filtros antes de
     *     paginar.
     * @param filters Totales bajo all, available, review y missing.
     * @param generatedAt Instante UTC en que se construye la estadística o evento.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record CatalogStatsResponse(
            long total,
            Map<String, Long> filters,
            LocalDateTime generatedAt) {}

    /**
     * Transporta una selección textual de aplicaciones para consumidores del contrato de descarga.
     *
     * @param appIds UUID de las aplicaciones del lote a enriquecer o consultar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record DownloadZipRequest(List<String> appIds) {}

    /**
     * Notifica una versión opaca del catálogo para que los clientes invaliden sus consultas.
     *
     * @param type Tipo de evento catalog.changed para invalidación del cliente.
     * @param version Versión del instalador, o token de cambio cuando el contrato describe un
     *     evento de catálogo.
     * @param generatedAt Instante UTC en que se construye la estadística o evento.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    public record CatalogChangeEvent(String type, String version, LocalDateTime generatedAt) {}
}

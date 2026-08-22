package es.ubu.batchdownloader.catalog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Implementa el componente {@code CatalogDtos}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class CatalogDtos {
    /**
     * Representa los datos inmutables de {@code AppListItem}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param slug Valor de {@code slug} incluido en el record.
     * @param packageId Valor de {@code packageId} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param tags Valor de {@code tags} incluido en el record.
     * @param operatingSystems Valor de {@code operatingSystems} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param sourceLabel Valor de {@code sourceLabel} incluido en el record.
     * @param resolutionStatus Valor de {@code resolutionStatus} incluido en el record.
     * @param validationStatus Valor de {@code validationStatus} incluido en el record.
     * @param downloadable Valor de {@code downloadable} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code AppSearchResponse}.
     *
     * @param data Valor de {@code data} incluido en el record.
     * @param page Valor de {@code page} incluido en el record.
     * @param pageSize Valor de {@code pageSize} incluido en el record.
     * @param total Valor de {@code total} incluido en el record.
     * @param alphabet Posiciones disponibles del índice alfabético.
     * @param requestedMode Valor de {@code requestedMode} incluido en el record.
     * @param appliedMode Valor de {@code appliedMode} incluido en el record.
     * @param modelVersion Valor de {@code modelVersion} incluido en el record.
     * @param indexVersion Valor de {@code indexVersion} incluido en el record.
     * @param degradedReason Valor de {@code degradedReason} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
         * Inicializa una instancia de {@code AppSearchResponse}.
         *
         * @param data Valor de {@code data} utilizado por la operación.
         * @param page Número de página solicitado.
         * @param pageSize Número máximo de elementos incluidos en una página.
         * @param total Valor de {@code total} utilizado por la operación.
         */
        public AppSearchResponse(List<AppListItem> data, int page, int pageSize, long total) {
            this(data, page, pageSize, total, List.of(), "lexical", "lexical", null, null, null);
        }
    }

    /**
     * Sitúa el primer resultado de una letra dentro de la paginación alfabética.
     *
     * @param letter Letra representada por la entrada.
     * @param page Primera página que contiene una aplicación de esa letra.
     * @param count Número de aplicaciones de esa letra bajo los filtros activos.
     */
    public record CatalogAlphabetEntry(String letter, int page, long count) {}

    /**
     * Representa los datos inmutables de {@code FacetItem}.
     *
     * @param label Valor de {@code label} incluido en el record.
     * @param value Valor de {@code value} incluido en el record.
     * @param normalizedValue Valor de {@code normalizedValue} incluido en el record.
     * @param letter Valor de {@code letter} incluido en el record.
     * @param count Valor de {@code count} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record FacetItem(
            String label,
            String value,
            String normalizedValue,
            String letter,
            long count) {}

    /**
     * Representa los datos inmutables de {@code CatalogFacetsResponse}.
     *
     * @param tags Valor de {@code tags} incluido en el record.
     * @param publishers Valor de {@code publishers} incluido en el record.
     * @param requestedMode Valor de {@code requestedMode} incluido en el record.
     * @param appliedMode Valor de {@code appliedMode} incluido en el record.
     * @param modelVersion Valor de {@code modelVersion} incluido en el record.
     * @param indexVersion Valor de {@code indexVersion} incluido en el record.
     * @param degradedReason Valor de {@code degradedReason} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
         * Inicializa una instancia de {@code CatalogFacetsResponse}.
         *
         * @param tags Valor de {@code tags} utilizado por la operación.
         * @param publishers Valor de {@code publishers} utilizado por la operación.
         */
        public CatalogFacetsResponse(List<FacetItem> tags, List<FacetItem> publishers) {
            this(tags, publishers, "lexical", "lexical", null, null, null);
        }
    }

    /**
     * Representa los datos inmutables de {@code DownloadOption}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param extension Valor de {@code extension} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @param version Valor de {@code version} incluido en el record.
     * @param isLatest Valor de {@code isLatest} incluido en el record.
     * @param versionStatus Valor de {@code versionStatus} incluido en el record.
     * @param sourceLabel Valor de {@code sourceLabel} incluido en el record.
     * @param score Valor de {@code score} incluido en el record.
     * @param finalDomain Valor de {@code finalDomain} incluido en el record.
     * @param isPrimary Valor de {@code isPrimary} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
            boolean isPrimary) {}

    /**
     * Representa los datos inmutables de {@code AppDetails}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param slug Valor de {@code slug} incluido en el record.
     * @param packageId Valor de {@code packageId} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param tags Valor de {@code tags} incluido en el record.
     * @param operatingSystems Valor de {@code operatingSystems} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param originUrl Valor de {@code originUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param installerFilename Valor de {@code installerFilename} incluido en el record.
     * @param installerType Valor de {@code installerType} incluido en el record.
     * @param contentType Valor de {@code contentType} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param finalDomain Valor de {@code finalDomain} incluido en el record.
     * @param score Valor de {@code score} incluido en el record.
     * @param resolutionStatus Valor de {@code resolutionStatus} incluido en el record.
     * @param validationStatus Valor de {@code validationStatus} incluido en el record.
     * @param downloadable Valor de {@code downloadable} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @param sourceLabel Valor de {@code sourceLabel} incluido en el record.
     * @param checkedAt Valor de {@code checkedAt} incluido en el record.
     * @param expiresAt Valor de {@code expiresAt} incluido en el record.
     * @param downloadOptions Valor de {@code downloadOptions} incluido en el record.
     * @param notes Valor de {@code notes} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code LastScrapeRun}.
     *
     * @param status Valor de {@code status} incluido en el record.
     * @param startedAt Valor de {@code startedAt} incluido en el record.
     * @param heartbeatAt Valor de {@code heartbeatAt} incluido en el record.
     * @param finishedAt Valor de {@code finishedAt} incluido en el record.
     * @param appsDiscovered Valor de {@code appsDiscovered} incluido en el record.
     * @param appsResolved Valor de {@code appsResolved} incluido en el record.
     * @param appsFailed Valor de {@code appsFailed} incluido en el record.
     * @param appsSkipped Valor de {@code appsSkipped} incluido en el record.
     * @param currentPackageId Valor de {@code currentPackageId} incluido en el record.
     * @param currentAppName Valor de {@code currentAppName} incluido en el record.
     * @param currentPhase Valor de {@code currentPhase} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record LastScrapeRun(
            String status,
            LocalDateTime startedAt,
            LocalDateTime heartbeatAt,
            LocalDateTime finishedAt,
            int appsDiscovered,
            int appsResolved,
            int appsFailed,
            int appsSkipped,
            String currentPackageId,
            String currentAppName,
            String currentPhase) {}

    /**
     * Representa los datos inmutables de {@code CatalogStatsResponse}.
     *
     * @param total Valor de {@code total} incluido en el record.
     * @param filters Valor de {@code filters} incluido en el record.
     * @param lastScrape Valor de {@code lastScrape} incluido en el record.
     * @param generatedAt Valor de {@code generatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record CatalogStatsResponse(
            long total,
            Map<String, Long> filters,
            LastScrapeRun lastScrape,
            LocalDateTime generatedAt) {}

    /**
     * Representa los datos inmutables de {@code DownloadZipRequest}.
     *
     * @param appIds Valor de {@code appIds} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DownloadZipRequest(List<String> appIds) {}

    /**
     * Representa los datos inmutables de {@code CatalogChangeEvent}.
     *
     * @param type Valor de {@code type} incluido en el record.
     * @param version Valor de {@code version} incluido en el record.
     * @param generatedAt Valor de {@code generatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record CatalogChangeEvent(String type, String version, LocalDateTime generatedAt) {}
}

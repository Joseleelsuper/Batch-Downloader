package es.ubu.batchdownloader.catalog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class CatalogDtos {
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

    public record AppSearchResponse(
            List<AppListItem> data,
            int page,
            int pageSize,
            long total,
            String requestedMode,
            String appliedMode,
            String modelVersion,
            String indexVersion,
            String degradedReason) {
        public AppSearchResponse(List<AppListItem> data, int page, int pageSize, long total) {
            this(data, page, pageSize, total, "lexical", "lexical", null, null, null);
        }
    }

    public record FacetItem(
            String label,
            String value,
            String normalizedValue,
            String letter,
            long count) {}

    public record CatalogFacetsResponse(
            List<FacetItem> tags,
            List<FacetItem> publishers,
            String requestedMode,
            String appliedMode,
            String modelVersion,
            String indexVersion,
            String degradedReason) {
        public CatalogFacetsResponse(List<FacetItem> tags, List<FacetItem> publishers) {
            this(tags, publishers, "lexical", "lexical", null, null, null);
        }
    }

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

    public record CatalogStatsResponse(
            long total,
            Map<String, Long> filters,
            LastScrapeRun lastScrape,
            LocalDateTime generatedAt) {}

    public record DownloadZipRequest(List<String> appIds) {}

    public record CatalogChangeEvent(String type, String version, LocalDateTime generatedAt) {}
}

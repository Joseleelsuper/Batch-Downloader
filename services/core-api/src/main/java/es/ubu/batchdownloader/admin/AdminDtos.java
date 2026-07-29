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

public class AdminDtos {
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

    public record PatchAppRequest(
            String name,
            String publisher,
            String description,
            String longDescription,
            String iconUrl,
            String officialUrl,
            String latestVersion,
            String appStatus) {}

    public record ReplaceTagsRequest(List<String> tags) {}

    public record PatchSourceRequest(
            String operatingSystem,
            String architecture,
            String initialUrl,
            String resolverType,
            String resolutionStatus,
            String validationStatus) {}

    public record ManualInstallerInspectionRequest(
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String installerUrl,
            @Valid WebsiteAppInstallerUrls installerUrls,
            @NotBlank
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String sourcePageUrl) {
        public ManualInstallerInspectionRequest {
            if (installerUrls == null) {
                installerUrls = new WebsiteAppInstallerUrls(null, null, null);
            }
        }

        @AssertTrue(message = "Debe indicarse al menos una URI de instalador.")
        public boolean hasInstallerUrl() {
            return hasText(installerUrl)
                    || hasText(installerUrls.windows())
                    || hasText(installerUrls.macos())
                    || hasText(installerUrls.linux());
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record ManualFieldSuggestion(String value, String source) {}

    public record ManualInstallerSuggestions(
            ManualFieldSuggestion name,
            ManualFieldSuggestion publisher,
            ManualFieldSuggestion officialUrl,
            ManualFieldSuggestion latestVersion,
            ManualFieldSuggestion description,
            ManualFieldSuggestion longDescription,
            ManualFieldSuggestion iconUrl) {}

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

    public record ManualInstallerAiState(
            String status,
            String provider,
            String model) {}

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

    public record ManualInstallerApplyResult(
            String appId,
            String sourceRef,
            List<String> sourceRefs,
            long appVersion,
            String catalogStatus,
            List<String> warnings) {
        public ManualInstallerApplyResult {
            sourceRefs = sourceRefs == null
                    ? (sourceRef == null ? List.of() : List.of(sourceRef))
                    : List.copyOf(sourceRefs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record ManualInstallerApplyResponse(
            AppDetails application,
            String sourceRef,
            List<String> sourceRefs,
            List<String> warnings) {}

    public record WebsiteAppDiscoveryRequest(
            @NotBlank
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String officialUrl,
            @Valid WebsiteAppInstallerUrls installerUrls) {
        public WebsiteAppDiscoveryRequest {
            if (installerUrls == null) {
                installerUrls = new WebsiteAppInstallerUrls(null, null, null);
            }
        }
    }

    public record WebsiteAppInstallerUrls(
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String windows,
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String macos,
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String linux) {}

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

    public record WebsiteAppDiscoveryApplyResult(
            String appId,
            long appVersion,
            String catalogStatus,
            int installerCount,
            List<String> warnings) {}

    public record WebsiteAppDiscoveryApplyResponse(
            AppDetails application,
            int installerCount,
            List<String> warnings) {}

    public record ScraperRunSummary(
            String id,
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
            String currentPhase,
            boolean stopRequested,
            LocalDateTime pausedAt,
            String errorSummary) {}

    public record ResolverLogItem(
            String id,
            String phase,
            String status,
            String message,
            String safeMetadata,
            LocalDateTime createdAt) {}

    public record ScraperQueueItem(
            String id,
            String packageId,
            String appName,
            String status,
            int attempts,
            LocalDateTime updatedAt) {}

    public record ScraperQueueState(
            String queue,
            long queued,
            long inProgress,
            long completed,
            long discarded,
            long failed,
            List<ScraperQueueItem> items) {}

    public record ScraperMetricItem(
            int available,
            int review,
            int unavailable,
            int queuedSearcherFilter,
            int queuedFilterScraper,
            int queuedScraperSoFilter,
            int queuedSoFilterDescriptor,
            LocalDateTime capturedAt) {}

    public record ScraperSnapshotItem(
            String stage,
            String packageId,
            String appName,
            String url,
            String html,
            LocalDateTime capturedAt) {}

    public record ScraperEvent(
            String type,
            String version,
            List<ScraperQueueState> queues,
            List<ScraperMetricItem> metrics,
            List<ScraperSnapshotItem> snapshots,
            LocalDateTime generatedAt) {}

    public record ScraperCommandRequest(@NotBlank String command) {}

    public record ScraperQueueMaintenanceResult(String action, int affected) {}

    public record AdminAuditItem(
            String actor,
            String action,
            String targetType,
            String targetId,
            String safeMetadata,
            LocalDateTime createdAt) {}

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

    public record CreateSoftwareRequest(
            @NotBlank String requestedName,
            @NotBlank String officialUrl,
            String description,
            String requesterEmail) {}
}

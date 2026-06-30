package es.ubu.batchdownloader.admin;

import jakarta.validation.constraints.NotBlank;
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

    public record ScraperCommandRequest(@NotBlank String command) {}

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

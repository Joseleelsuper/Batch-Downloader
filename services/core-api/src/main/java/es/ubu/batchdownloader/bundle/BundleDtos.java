package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public class BundleDtos {
    public record PlatformAvailability(
            String operatingSystem,
            int downloadableAppCount,
            List<AppListItem> previewApps) {}

    public record BundleSummary(
            String id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            int appCount,
            List<String> operatingSystems,
            List<PlatformAvailability> platformAvailability,
            List<String> tags,
            List<AppListItem> previewApps,
            LocalDateTime updatedAt) {}

    public record BundleDetails(
            String id,
            String slug,
            String name,
            String description,
            String type,
            String visibility,
            int starCount,
            int appCount,
            List<String> operatingSystems,
            List<PlatformAvailability> platformAvailability,
            List<String> tags,
            List<AppListItem> apps,
            LocalDateTime updatedAt) {}

    public record BundleSearchResponse(List<BundleSummary> data, int page, int pageSize, long total) {}

    public record UpsertBundleRequest(
            @NotBlank String name,
            String description,
            String slug,
            String type,
            String visibility,
            List<String> tags,
            @Size(max = 100) List<String> appIds) {}
}

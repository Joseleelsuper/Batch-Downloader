package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementa el componente {@code BundleDtos}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class BundleDtos {
    /**
     * Representa los datos inmutables de {@code PlatformAvailability}.
     *
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param downloadableAppCount Valor de {@code downloadableAppCount} incluido en el record.
     * @param previewApps Valor de {@code previewApps} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record PlatformAvailability(
            String operatingSystem,
            int downloadableAppCount,
            List<AppListItem> previewApps) {}

    /**
     * Representa los datos inmutables de {@code BundleSummary}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param slug Valor de {@code slug} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param visibility Valor de {@code visibility} incluido en el record.
     * @param starCount Valor de {@code starCount} incluido en el record.
     * @param appCount Valor de {@code appCount} incluido en el record.
     * @param operatingSystems Valor de {@code operatingSystems} incluido en el record.
     * @param platformAvailability Valor de {@code platformAvailability} incluido en el record.
     * @param tags Valor de {@code tags} incluido en el record.
     * @param previewApps Valor de {@code previewApps} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code BundleDetails}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param slug Valor de {@code slug} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param visibility Valor de {@code visibility} incluido en el record.
     * @param starCount Valor de {@code starCount} incluido en el record.
     * @param appCount Valor de {@code appCount} incluido en el record.
     * @param operatingSystems Valor de {@code operatingSystems} incluido en el record.
     * @param platformAvailability Valor de {@code platformAvailability} incluido en el record.
     * @param tags Valor de {@code tags} incluido en el record.
     * @param apps Valor de {@code apps} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code BundleSearchResponse}.
     *
     * @param data Valor de {@code data} incluido en el record.
     * @param page Valor de {@code page} incluido en el record.
     * @param pageSize Valor de {@code pageSize} incluido en el record.
     * @param total Valor de {@code total} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record BundleSearchResponse(List<BundleSummary> data, int page, int pageSize, long total) {}

    /**
     * Representa los datos inmutables de {@code UpsertBundleRequest}.
     *
     * @param name Valor de {@code name} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param slug Valor de {@code slug} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param visibility Valor de {@code visibility} incluido en el record.
     * @param tags Valor de {@code tags} incluido en el record.
     * @param appIds Valor de {@code appIds} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record UpsertBundleRequest(
            @NotBlank String name,
            String description,
            String slug,
            String type,
            String visibility,
            List<String> tags,
            @Size(max = 100) List<String> appIds) {}

    /** Resumen editable que nunca expone campos administrativos ni de propietario. */
    public record OwnBundleSummary(
            String id,
            String slug,
            String name,
            String description,
            String visibility,
            int appCount,
            List<String> tags,
            LocalDateTime updatedAt,
            long version) {}

    public record OwnBundleDetails(
            String id,
            String slug,
            String name,
            String description,
            String visibility,
            int appCount,
            List<String> tags,
            List<AppListItem> apps,
            LocalDateTime updatedAt,
            long version) {}

    public record OwnBundlePage(
            List<OwnBundleSummary> data, int page, int pageSize, long total) {}

    public record CreateOwnBundleRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 4000) String description,
            @Size(max = 180) String slug,
            @Size(max = 30) List<@NotBlank @Size(max = 80) String> tags,
            @Size(max = 100) List<@NotBlank String> appIds) {}

    public record UpdateOwnBundleRequest(
            @NotBlank @Size(max = 160) String name,
            @Size(max = 4000) String description,
            @Size(max = 180) String slug,
            @NotBlank String visibility,
            @Size(max = 30) List<@NotBlank @Size(max = 80) String> tags,
            @Size(max = 100) List<@NotBlank String> appIds,
            @NotNull Long expectedVersion) {}
}

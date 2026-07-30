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

/**
 * Implementa el componente {@code AdminDtos}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class AdminDtos {
    /**
     * Representa los datos inmutables de {@code UpsertAppRequest}.
     *
     * @param winstallId Valor de {@code winstallId} incluido en el record.
     * @param slug Valor de {@code slug} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param appStatus Valor de {@code appStatus} incluido en el record.
     * @param tags Valor de {@code tags} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code PatchAppRequest}.
     *
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param appStatus Valor de {@code appStatus} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code ReplaceTagsRequest}.
     *
     * @param tags Valor de {@code tags} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ReplaceTagsRequest(List<String> tags) {}

    /**
     * Representa los datos inmutables de {@code PatchSourceRequest}.
     *
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @param initialUrl Valor de {@code initialUrl} incluido en el record.
     * @param resolverType Valor de {@code resolverType} incluido en el record.
     * @param resolutionStatus Valor de {@code resolutionStatus} incluido en el record.
     * @param validationStatus Valor de {@code validationStatus} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record PatchSourceRequest(
            String operatingSystem,
            String architecture,
            String initialUrl,
            String resolverType,
            String resolutionStatus,
            String validationStatus) {}

    /**
     * Representa los datos inmutables de {@code ManualInstallerInspectionRequest}.
     *
     * @param installerUrl Valor de {@code installerUrl} incluido en el record.
     * @param installerUrls Valor de {@code installerUrls} incluido en el record.
     * @param sourcePageUrl Valor de {@code sourcePageUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
         * Inicializa una instancia de {@code ManualInstallerInspectionRequest}.
         *
         * @param installerUrl Dirección de {@code installer} que debe procesarse.
         * @param installerUrls Valor de {@code installerUrls} utilizado por la operación.
         * @param sourcePageUrl Dirección de {@code sourcePage} que debe procesarse.
         */
        public ManualInstallerInspectionRequest {
            if (installerUrls == null) {
                installerUrls = new WebsiteAppInstallerUrls(null, null, null);
            }
        }

        /**
         * Indica si existe el recurso mediante {@code hasInstallerUrl}.
         *
         * @return Indica si se cumple la condición evaluada.
         */
        @AssertTrue(message = "Debe indicarse al menos una URI de instalador.")
        public boolean hasInstallerUrl() {
            return hasText(installerUrl)
                    || hasText(installerUrls.windows())
                    || hasText(installerUrls.macos())
                    || hasText(installerUrls.linux());
        }

        /**
         * Indica si existe el recurso mediante {@code hasText}.
         *
         * @param value Valor que debe procesarse.
         * @return Indica si se cumple la condición evaluada.
         */
        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    /**
     * Representa los datos inmutables de {@code ManualFieldSuggestion}.
     *
     * @param value Valor de {@code value} incluido en el record.
     * @param source Valor de {@code source} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ManualFieldSuggestion(String value, String source) {}

    /**
     * Representa los datos inmutables de {@code ManualInstallerSuggestions}.
     *
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code ManualInstallerTechnicalData}.
     *
     * @param finalDomain Valor de {@code finalDomain} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param extension Valor de {@code extension} incluido en el record.
     * @param contentType Valor de {@code contentType} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param version Valor de {@code version} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @param platformRequired Valor de {@code platformRequired} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code ManualInstallerAiState}.
     *
     * @param status Valor de {@code status} incluido en el record.
     * @param provider Valor de {@code provider} incluido en el record.
     * @param model Valor de {@code model} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ManualInstallerAiState(
            String status,
            String provider,
            String model) {}

    /**
     * Representa los datos inmutables de {@code ManualInstallerInspection}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param phase Valor de {@code phase} incluido en el record.
     * @param expectedAppVersion Valor de {@code expectedAppVersion} incluido en el record.
     * @param warnings Valor de {@code warnings} incluido en el record.
     * @param suggestions Valor de {@code suggestions} incluido en el record.
     * @param installer Valor de {@code installer} incluido en el record.
     * @param installers Valor de {@code installers} incluido en el record.
     * @param ai Valor de {@code ai} incluido en el record.
     * @param errorCode Valor de {@code errorCode} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param createdAt Valor de {@code createdAt} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @param expiresAt Valor de {@code expiresAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
         * Inicializa una instancia de {@code ManualInstallerInspection}.
         *
         * @param id Identificador del recurso sobre el que se actúa.
         * @param appId Identificador de {@code app} utilizado por la operación.
         * @param status Estado utilizado para filtrar o actualizar el recurso.
         * @param phase Valor de {@code phase} utilizado por la operación.
         * @param expectedAppVersion Valor esperado de {@code appVersion}.
         * @param warnings Valor de {@code warnings} utilizado por la operación.
         * @param suggestions Valor de {@code suggestions} utilizado por la operación.
         * @param installer Valor de {@code installer} utilizado por la operación.
         * @param installers Valor de {@code installers} utilizado por la operación.
         * @param ai Valor de {@code ai} utilizado por la operación.
         * @param errorCode Valor de {@code errorCode} utilizado por la operación.
         * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
         * @param createdAt Valor de {@code createdAt} utilizado por la operación.
         * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
         * @param expiresAt Valor de {@code expiresAt} utilizado por la operación.
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
     * Representa los datos inmutables de {@code ManualInstallerApplyRequest}.
     *
     * @param expectedAppVersion Valor de {@code expectedAppVersion} incluido en el record.
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code ManualInstallerApplyResult}.
     *
     * @param appId Valor de {@code appId} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param sourceRefs Valor de {@code sourceRefs} incluido en el record.
     * @param appVersion Valor de {@code appVersion} incluido en el record.
     * @param catalogStatus Valor de {@code catalogStatus} incluido en el record.
     * @param warnings Valor de {@code warnings} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ManualInstallerApplyResult(
            String appId,
            String sourceRef,
            List<String> sourceRefs,
            long appVersion,
            String catalogStatus,
            List<String> warnings) {
        /**
         * Inicializa una instancia de {@code ManualInstallerApplyResult}.
         *
         * @param appId Identificador de {@code app} utilizado por la operación.
         * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
         * @param sourceRefs Valor de {@code sourceRefs} utilizado por la operación.
         * @param appVersion Valor de {@code appVersion} utilizado por la operación.
         * @param catalogStatus Valor de {@code catalogStatus} utilizado por la operación.
         * @param warnings Valor de {@code warnings} utilizado por la operación.
         */
        public ManualInstallerApplyResult {
            sourceRefs = sourceRefs == null
                    ? (sourceRef == null ? List.of() : List.of(sourceRef))
                    : List.copyOf(sourceRefs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Representa los datos inmutables de {@code ManualInstallerApplyResponse}.
     *
     * @param application Valor de {@code application} incluido en el record.
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param sourceRefs Valor de {@code sourceRefs} incluido en el record.
     * @param warnings Valor de {@code warnings} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ManualInstallerApplyResponse(
            AppDetails application,
            String sourceRef,
            List<String> sourceRefs,
            List<String> warnings) {}

    /**
     * Representa los datos inmutables de {@code WebsiteAppDiscoveryRequest}.
     *
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param installerUrls Valor de {@code installerUrls} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record WebsiteAppDiscoveryRequest(
            @NotBlank
            @Size(max = 2048)
            @Pattern(regexp = "(?i)^https://.+")
            String officialUrl,
            @Valid WebsiteAppInstallerUrls installerUrls) {
        /**
         * Inicializa una instancia de {@code WebsiteAppDiscoveryRequest}.
         *
         * @param officialUrl Dirección de {@code official} que debe procesarse.
         * @param installerUrls Valor de {@code installerUrls} utilizado por la operación.
         */
        public WebsiteAppDiscoveryRequest {
            if (installerUrls == null) {
                installerUrls = new WebsiteAppInstallerUrls(null, null, null);
            }
        }
    }

    /**
     * Representa los datos inmutables de {@code WebsiteAppInstallerUrls}.
     *
     * @param windows Valor de {@code windows} incluido en el record.
     * @param macos Valor de {@code macos} incluido en el record.
     * @param linux Valor de {@code linux} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record WebsiteAppInstallerUrls(
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String windows,
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String macos,
            @Size(max = 2048) @Pattern(regexp = "(?i)^https://.+") String linux) {}

    /**
     * Representa los datos inmutables de {@code WebsiteAppDiscoveryInstaller}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param finalDomain Valor de {@code finalDomain} incluido en el record.
     * @param filename Valor de {@code filename} incluido en el record.
     * @param extension Valor de {@code extension} incluido en el record.
     * @param contentType Valor de {@code contentType} incluido en el record.
     * @param sizeBytes Valor de {@code sizeBytes} incluido en el record.
     * @param version Valor de {@code version} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code WebsiteAppDiscovery}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param phase Valor de {@code phase} incluido en el record.
     * @param warnings Valor de {@code warnings} incluido en el record.
     * @param providedInstallerPlatforms Valor de {@code providedInstallerPlatforms} incluido en el
     *     record.
     * @param suggestions Valor de {@code suggestions} incluido en el record.
     * @param installers Valor de {@code installers} incluido en el record.
     * @param ai Valor de {@code ai} incluido en el record.
     * @param errorCode Valor de {@code errorCode} incluido en el record.
     * @param appliedAppId Valor de {@code appliedAppId} incluido en el record.
     * @param createdAt Valor de {@code createdAt} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @param expiresAt Valor de {@code expiresAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code WebsiteAppDiscoveryApplyRequest}.
     *
     * @param name Valor de {@code name} incluido en el record.
     * @param publisher Valor de {@code publisher} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param latestVersion Valor de {@code latestVersion} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param longDescription Valor de {@code longDescription} incluido en el record.
     * @param iconUrl Valor de {@code iconUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Representa los datos inmutables de {@code WebsiteAppDiscoveryApplyResult}.
     *
     * @param appId Valor de {@code appId} incluido en el record.
     * @param appVersion Valor de {@code appVersion} incluido en el record.
     * @param catalogStatus Valor de {@code catalogStatus} incluido en el record.
     * @param installerCount Valor de {@code installerCount} incluido en el record.
     * @param warnings Valor de {@code warnings} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record WebsiteAppDiscoveryApplyResult(
            String appId,
            long appVersion,
            String catalogStatus,
            int installerCount,
            List<String> warnings) {}

    /**
     * Representa los datos inmutables de {@code WebsiteAppDiscoveryApplyResponse}.
     *
     * @param application Valor de {@code application} incluido en el record.
     * @param installerCount Valor de {@code installerCount} incluido en el record.
     * @param warnings Valor de {@code warnings} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record WebsiteAppDiscoveryApplyResponse(
            AppDetails application,
            int installerCount,
            List<String> warnings) {}

    /**
     * Representa los datos inmutables de {@code ScraperRunSummary}.
     *
     * @param id Valor de {@code id} incluido en el record.
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
     * @param stopRequested Valor de {@code stopRequested} incluido en el record.
     * @param pausedAt Valor de {@code pausedAt} incluido en el record.
     * @param errorSummary Valor de {@code errorSummary} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code ResolverLogItem}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param phase Valor de {@code phase} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param message Valor de {@code message} incluido en el record.
     * @param safeMetadata Valor de {@code safeMetadata} incluido en el record.
     * @param createdAt Valor de {@code createdAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ResolverLogItem(
            String id,
            String phase,
            String status,
            String message,
            String safeMetadata,
            LocalDateTime createdAt) {}

    /**
     * Representa los datos inmutables de {@code ScraperQueueItem}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param packageId Valor de {@code packageId} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param attempts Valor de {@code attempts} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperQueueItem(
            String id,
            String packageId,
            String appName,
            String status,
            int attempts,
            LocalDateTime updatedAt) {}

    /**
     * Representa los datos inmutables de {@code ScraperQueueState}.
     *
     * @param queue Valor de {@code queue} incluido en el record.
     * @param queued Valor de {@code queued} incluido en el record.
     * @param inProgress Valor de {@code inProgress} incluido en el record.
     * @param completed Valor de {@code completed} incluido en el record.
     * @param discarded Valor de {@code discarded} incluido en el record.
     * @param failed Valor de {@code failed} incluido en el record.
     * @param items Valor de {@code items} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperQueueState(
            String queue,
            long queued,
            long inProgress,
            long completed,
            long discarded,
            long failed,
            List<ScraperQueueItem> items) {}

    /**
     * Representa los datos inmutables de {@code ScraperMetricItem}.
     *
     * @param available Valor de {@code available} incluido en el record.
     * @param review Valor de {@code review} incluido en el record.
     * @param unavailable Valor de {@code unavailable} incluido en el record.
     * @param queuedSearcherFilter Valor de {@code queuedSearcherFilter} incluido en el record.
     * @param queuedFilterScraper Valor de {@code queuedFilterScraper} incluido en el record.
     * @param queuedScraperSoFilter Valor de {@code queuedScraperSoFilter} incluido en el record.
     * @param queuedSoFilterDescriptor Valor de {@code queuedSoFilterDescriptor} incluido en el
     *     record.
     * @param capturedAt Valor de {@code capturedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperMetricItem(
            int available,
            int review,
            int unavailable,
            int queuedSearcherFilter,
            int queuedFilterScraper,
            int queuedScraperSoFilter,
            int queuedSoFilterDescriptor,
            LocalDateTime capturedAt) {}

    /**
     * Representa los datos inmutables de {@code ScraperSnapshotItem}.
     *
     * @param stage Valor de {@code stage} incluido en el record.
     * @param packageId Valor de {@code packageId} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param url Valor de {@code url} incluido en el record.
     * @param html Valor de {@code html} incluido en el record.
     * @param capturedAt Valor de {@code capturedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperSnapshotItem(
            String stage,
            String packageId,
            String appName,
            String url,
            String html,
            LocalDateTime capturedAt) {}

    /**
     * Representa los datos inmutables de {@code ScraperEvent}.
     *
     * @param type Valor de {@code type} incluido en el record.
     * @param version Valor de {@code version} incluido en el record.
     * @param queues Valor de {@code queues} incluido en el record.
     * @param metrics Valor de {@code metrics} incluido en el record.
     * @param snapshots Valor de {@code snapshots} incluido en el record.
     * @param generatedAt Valor de {@code generatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperEvent(
            String type,
            String version,
            List<ScraperQueueState> queues,
            List<ScraperMetricItem> metrics,
            List<ScraperSnapshotItem> snapshots,
            LocalDateTime generatedAt) {}

    /**
     * Representa los datos inmutables de {@code ScraperCommandRequest}.
     *
     * @param command Valor de {@code command} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperCommandRequest(@NotBlank String command) {}

    /**
     * Representa los datos inmutables de {@code ScraperQueueMaintenanceResult}.
     *
     * @param action Valor de {@code action} incluido en el record.
     * @param affected Valor de {@code affected} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ScraperQueueMaintenanceResult(String action, int affected) {}

    /**
     * Representa los datos inmutables de {@code AdminAuditItem}.
     *
     * @param actor Valor de {@code actor} incluido en el record.
     * @param action Valor de {@code action} incluido en el record.
     * @param targetType Valor de {@code targetType} incluido en el record.
     * @param targetId Valor de {@code targetId} incluido en el record.
     * @param safeMetadata Valor de {@code safeMetadata} incluido en el record.
     * @param createdAt Valor de {@code createdAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record AdminAuditItem(
            String actor,
            String action,
            String targetType,
            String targetId,
            String safeMetadata,
            LocalDateTime createdAt) {}

    /**
     * Representa los datos inmutables de {@code SoftwareRequestItem}.
     *
     * @param id Valor de {@code id} incluido en el record.
     * @param requestedName Valor de {@code requestedName} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param generatedDescription Valor de {@code generatedDescription} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @param requesterEmail Valor de {@code requesterEmail} incluido en el record.
     * @param createdAt Valor de {@code createdAt} incluido en el record.
     * @param updatedAt Valor de {@code updatedAt} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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

    /**
     * Representa los datos inmutables de {@code CreateSoftwareRequest}.
     *
     * @param requestedName Valor de {@code requestedName} incluido en el record.
     * @param officialUrl Valor de {@code officialUrl} incluido en el record.
     * @param description Valor de {@code description} incluido en el record.
     * @param requesterEmail Valor de {@code requesterEmail} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record CreateSoftwareRequest(
            @NotBlank String requestedName,
            @NotBlank String officialUrl,
            String description,
            String requesterEmail) {}
}

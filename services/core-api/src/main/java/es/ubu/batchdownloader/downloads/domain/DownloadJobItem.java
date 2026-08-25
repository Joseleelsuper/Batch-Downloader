package es.ubu.batchdownloader.downloads.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementa el componente {@code DownloadJobItem}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class DownloadJobItem {
    /**
     * Estado {@code id} mantenido por {@code DownloadJobItem}.
     */
    private final UUID id;
    /**
     * Estado {@code appId} mantenido por {@code DownloadJobItem}.
     */
    private final UUID appId;
    /**
     * Estado {@code sourceRef} mantenido por {@code DownloadJobItem}.
     */
    private final UUID sourceRef;
    /**
     * Estado {@code appName} mantenido por {@code DownloadJobItem}.
     */
    private final String appName;
    /**
     * Estado {@code officialPageUrl} mantenido por {@code DownloadJobItem}.
     */
    private final String officialPageUrl;
    /**
     * Estado {@code status} mantenido por {@code DownloadJobItem}.
     */
    private DownloadItemStatus status;
    /**
     * Estado {@code bytesDownloaded} mantenido por {@code DownloadJobItem}.
     */
    private long bytesDownloaded;
    /**
     * Estado {@code sha256} mantenido por {@code DownloadJobItem}.
     */
    private String sha256;
    /**
     * Estado {@code errorCode} mantenido por {@code DownloadJobItem}.
     */
    private String errorCode;
    /**
     * Estado {@code createdAt} mantenido por {@code DownloadJobItem}.
     */
    private final Instant createdAt;
    /**
     * Estado {@code updatedAt} mantenido por {@code DownloadJobItem}.
     */
    private Instant updatedAt;
    /**
     * Estado {@code version} mantenido por {@code DownloadJobItem}.
     */
    private long version;

    /**
     * Inicializa una instancia de {@code DownloadJobItem}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
     * @param appName Valor de {@code appName} utilizado por la operación.
     * @param officialPageUrl Dirección de {@code officialPage} que debe procesarse.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param bytesDownloaded Valor de {@code bytesDownloaded} utilizado por la operación.
     * @param sha256 Valor de {@code sha256} utilizado por la operación.
     * @param errorCode Valor de {@code errorCode} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
     * @param version Valor de {@code version} utilizado por la operación.
     */
    private DownloadJobItem(
            UUID id,
            UUID appId,
            UUID sourceRef,
            String appName,
            String officialPageUrl,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.appId = Objects.requireNonNull(appId);
        this.sourceRef = sourceRef;
        this.appName = normalizedName(appName, appId);
        this.officialPageUrl = normalizedOptionalText(officialPageUrl);
        this.status = Objects.requireNonNull(status);
        this.bytesDownloaded = Math.max(0, bytesDownloaded);
        this.sha256 = sha256;
        this.errorCode = errorCode;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.version = version;
    }

    /**
     * Ejecuta la operación {@code queued}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Resultado producido por {@code queued}.
     */
    public static DownloadJobItem queued(UUID appId, UUID sourceRef, Instant now) {
        return queued(appId, sourceRef, appId.toString(), null, now);
    }

    /**
     * Ejecuta la operación {@code queued}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
     * @param appName Valor de {@code appName} utilizado por la operación.
     * @param officialPageUrl Dirección de {@code officialPage} que debe procesarse.
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Resultado producido por {@code queued}.
     */
    public static DownloadJobItem queued(
            UUID appId, UUID sourceRef, String appName, String officialPageUrl, Instant now) {
        return new DownloadJobItem(
                UUID.randomUUID(), appId, Objects.requireNonNull(sourceRef), appName, officialPageUrl,
                DownloadItemStatus.QUEUED, 0, null, null, now, now, 0);
    }

    /**
     * Crea un item que se resolverá mediante un acceso a la página oficial.
     *
     * @param appId Identificador de la aplicación.
     * @param appName Nombre público de la aplicación.
     * @param officialPageUrl Página oficial que se incluirá en el acceso.
     * @param now Instante de creación.
     * @return Item manual listo para encolarse.
     */
    public static DownloadJobItem manual(
            UUID appId, String appName, String officialPageUrl, Instant now) {
        if (officialPageUrl == null || officialPageUrl.isBlank()) {
            throw new IllegalArgumentException("manual_download_requires_official_page");
        }
        return new DownloadJobItem(
                UUID.randomUUID(), appId, null, appName, officialPageUrl,
                DownloadItemStatus.QUEUED, 0, null, null, now, now, 0);
    }

    /**
     * Ejecuta la operación {@code rehydrate}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
     * @param appName Valor de {@code appName} utilizado por la operación.
     * @param officialPageUrl Dirección de {@code officialPage} que debe procesarse.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param bytesDownloaded Valor de {@code bytesDownloaded} utilizado por la operación.
     * @param sha256 Valor de {@code sha256} utilizado por la operación.
     * @param errorCode Valor de {@code errorCode} utilizado por la operación.
     * @param createdAt Valor de {@code createdAt} utilizado por la operación.
     * @param updatedAt Valor de {@code updatedAt} utilizado por la operación.
     * @param version Valor de {@code version} utilizado por la operación.
     * @return Resultado producido por {@code rehydrate}.
     */
    public static DownloadJobItem rehydrate(
            UUID id, UUID appId, UUID sourceRef, String appName, String officialPageUrl,
            DownloadItemStatus status, long bytesDownloaded,
            String sha256, String errorCode, Instant createdAt, Instant updatedAt, long version) {
        return new DownloadJobItem(
                id, appId, sourceRef, appName, officialPageUrl,
                status, bytesDownloaded, sha256, errorCode, createdAt, updatedAt, version);
    }

    /**
     * Ejecuta la operación {@code progress}.
     *
     * @param next Valor de {@code next} utilizado por la operación.
     * @param downloaded Valor de {@code downloaded} utilizado por la operación.
     * @param checksum Valor de {@code checksum} utilizado por la operación.
     * @param failure Valor de {@code failure} utilizado por la operación.
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void progress(DownloadItemStatus next, long downloaded, String checksum, String failure, Instant now) {
        if (status.terminal()) return;
        status = Objects.requireNonNull(next);
        bytesDownloaded = Math.max(bytesDownloaded, downloaded);
        sha256 = checksum;
        errorCode = failure;
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * Indica si puede realizarse la operación mediante {@code cancel}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     */
    public void cancel(Instant now) {
        if (!status.terminal()) progress(DownloadItemStatus.CANCELLED, bytesDownloaded, sha256, null, now);
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    public UUID id() { return id; }
    /**
     * Ejecuta la operación {@code appId}.
     *
     * @return Resultado producido por {@code appId}.
     */
    public UUID appId() { return appId; }
    /**
     * Ejecuta la operación {@code sourceRef}.
     *
     * @return Resultado producido por {@code sourceRef}.
     */
    public UUID sourceRef() { return sourceRef; }
    /**
     * Ejecuta la operación {@code appName}.
     *
     * @return Resultado producido por {@code appName}.
     */
    public String appName() { return appName; }
    /**
     * Ejecuta la operación {@code officialPageUrl}.
     *
     * @return Resultado producido por {@code officialPageUrl}.
     */
    public String officialPageUrl() { return officialPageUrl; }
    /**
     * Ejecuta la operación {@code status}.
     *
     * @return Resultado producido por {@code status}.
     */
    public DownloadItemStatus status() { return status; }
    /**
     * Ejecuta la operación {@code bytesDownloaded}.
     *
     * @return Resultado producido por {@code bytesDownloaded}.
     */
    public long bytesDownloaded() { return bytesDownloaded; }
    /**
     * Ejecuta la operación {@code sha256}.
     *
     * @return Resultado producido por {@code sha256}.
     */
    public String sha256() { return sha256; }
    /**
     * Ejecuta la operación {@code errorCode}.
     *
     * @return Resultado producido por {@code errorCode}.
     */
    public String errorCode() { return errorCode; }
    /**
     * Crea el recurso solicitado mediante {@code createdAt}.
     *
     * @return Resultado producido por {@code createdAt}.
     */
    public Instant createdAt() { return createdAt; }
    /**
     * Actualiza el recurso solicitado mediante {@code updatedAt}.
     *
     * @return Resultado producido por {@code updatedAt}.
     */
    public Instant updatedAt() { return updatedAt; }
    /**
     * Ejecuta la operación {@code version}.
     *
     * @return Resultado producido por {@code version}.
     */
    public long version() { return version; }

    /**
     * Normaliza el valor recibido mediante {@code normalizedName}.
     *
     * @param value Valor que debe procesarse.
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code normalizedName}.
     */
    private static String normalizedName(String value, UUID appId) {
        return value == null || value.isBlank() ? appId.toString() : value.strip();
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizedOptionalText}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code normalizedOptionalText}.
     */
    private static String normalizedOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

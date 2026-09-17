package es.ubu.batchdownloader.downloads.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mantiene la fuente exacta, página manual y progreso de una aplicación dentro del lote,
 * conservando resultados terminales individuales.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJob
 * @see es.ubu.batchdownloader.downloads.domain.DownloadItemStatus
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public final class DownloadJobItem {
    /**
     * UUID estable del trabajo o elemento representado.
     */
    private final UUID id;
    /**
     * UUID público de la aplicación del catálogo.
     */
    private final UUID appId;
    /**
     * UUID de la fuente exacta; null permite selección automática o representa una alternativa
     * manual.
     */
    private final UUID sourceRef;
    /**
     * Nombre visible de la aplicación conservado en el momento de admisión.
     */
    private final String appName;
    /**
     * Página oficial para la alternativa manual; no es una URL de instalador resuelta.
     */
    private final String officialPageUrl;
    /**
     * Estado del trabajo o elemento correspondiente al evento o proyección.
     */
    private DownloadItemStatus status;
    /**
     * Bytes transferidos del instalador; el dominio conserva el máximo recibido.
     */
    private long bytesDownloaded;
    /**
     * SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está disponible.
     */
    private String sha256;
    /**
     * Código seguro del fallo del elemento o null si no hay un fallo que comunicar.
     */
    private String errorCode;
    /**
     * Instante de creación del registro.
     */
    private final Instant createdAt;
    /**
     * Instante del último cambio de estado guardado.
     */
    private Instant updatedAt;
    /**
     * Versión persistida para concurrencia optimista.
     */
    private long version;

    /**
     * Normaliza nombre y página opcional y conserva un contador de bytes no negativo para el
     * elemento.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     *
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     *
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param bytesDownloaded Bytes transferidos del instalador; el dominio conserva el máximo
     *     recibido.
     *
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     *
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     *
     * @param createdAt Instante de creación del registro.
     * @param updatedAt Instante del último cambio de estado guardado.
     * @param version Versión persistida para concurrencia optimista.
     * @throws NullPointerException si faltan UUID, estado o fechas requeridas.
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
     * Crea un elemento QUEUED ligado al UUID exacto de la fuente seleccionada por el catálogo.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return elemento nuevo con transferencia e integridad todavía vacías.
     */
    public static DownloadJobItem queued(UUID appId, UUID sourceRef, Instant now) {
        return queued(appId, sourceRef, appId.toString(), null, now);
    }

    /**
     * Crea un elemento QUEUED ligado al UUID exacto de la fuente seleccionada por el catálogo.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     *
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return elemento nuevo con transferencia e integridad todavía vacías.
     */
    public static DownloadJobItem queued(
            UUID appId, UUID sourceRef, String appName, String officialPageUrl, Instant now) {
        return new DownloadJobItem(
                UUID.randomUUID(), appId, Objects.requireNonNull(sourceRef), appName, officialPageUrl,
                DownloadItemStatus.QUEUED, 0, null, null, now, now, 0);
    }

    /**
     * Crea una alternativa manual con página oficial y sin fuente automática para conservarla en el
     * ZIP.
     *
     * @param appId UUID público de la aplicación del catálogo.
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return elemento QUEUED que el worker convertirá en acceso manual.
     * @throws IllegalArgumentException si falta una página oficial no vacía.
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
     * Reconstruye identidad, fuente y progreso del elemento a partir de su registro persistido.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param appId UUID público de la aplicación del catálogo.
     * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     *
     * @param appName Nombre visible de la aplicación conservado en el momento de admisión.
     * @param officialPageUrl Página oficial para la alternativa manual; no es una URL de instalador
     *     resuelta.
     *
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param bytesDownloaded Bytes transferidos del instalador; el dominio conserva el máximo
     *     recibido.
     *
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     *
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     *
     * @param createdAt Instante de creación del registro.
     * @param updatedAt Instante del último cambio de estado guardado.
     * @param version Versión persistida para concurrencia optimista.
     * @return elemento con nombre y página normalizados y contador de bytes no negativo.
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
     * Actualiza un elemento todavía activo manteniendo el máximo de bytes recibido; los estados
     * terminales no se reabren.
     *
     * @param next Nuevo estado del elemento; los estados terminales anteriores no se modifican.
     * @param downloaded Bytes transferidos recibidos; no disminuyen el contador existente.
     * @param checksum Hash del contenido transferido o null mientras no se conoce.
     * @param failure Código seguro de fallo o null para borrar el anterior.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
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
     * Cancela solo elementos no terminales y conserva sus bytes y checksum para el historial.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     */
    public void cancel(Instant now) {
        if (!status.terminal()) progress(DownloadItemStatus.CANCELLED, bytesDownloaded, sha256, null, now);
    }

    /**
     * Restablece QUEUED, bytes e integridad para reiniciar el elemento después de un aplazamiento
     * del lote.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     */
    void requeue(Instant now) {
        status = DownloadItemStatus.QUEUED;
        bytesDownloaded = 0;
        sha256 = null;
        errorCode = null;
        updatedAt = Objects.requireNonNull(now);
    }

    /**
     * UUID estable del trabajo o elemento representado.
     *
     * @return UUID estable del trabajo o elemento representado.
     */
    public UUID id() { return id; }
    /**
     * UUID público de la aplicación del catálogo.
     *
     * @return UUID público de la aplicación del catálogo.
     */
    public UUID appId() { return appId; }
    /**
     * UUID de la fuente exacta; null permite selección automática o representa una alternativa
     * manual.
     *
     * @return UUID de la fuente exacta; null permite selección automática o representa una
     *     alternativa manual.
     */
    public UUID sourceRef() { return sourceRef; }
    /**
     * Nombre visible de la aplicación conservado en el momento de admisión.
     *
     * @return Nombre visible de la aplicación conservado en el momento de admisión.
     */
    public String appName() { return appName; }
    /**
     * Página oficial para la alternativa manual; no es una URL de instalador resuelta.
     *
     * @return Página oficial para la alternativa manual; no es una URL de instalador resuelta.
     */
    public String officialPageUrl() { return officialPageUrl; }
    /**
     * Estado del trabajo o elemento correspondiente al evento o proyección.
     *
     * @return Estado del trabajo o elemento correspondiente al evento o proyección.
     */
    public DownloadItemStatus status() { return status; }
    /**
     * Bytes transferidos del instalador; el dominio conserva el máximo recibido.
     *
     * @return Bytes transferidos del instalador; el dominio conserva el máximo recibido.
     */
    public long bytesDownloaded() { return bytesDownloaded; }
    /**
     * SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está disponible.
     *
     * @return SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     */
    public String sha256() { return sha256; }
    /**
     * Código seguro del fallo del elemento o null si no hay un fallo que comunicar.
     *
     * @return Código seguro del fallo del elemento o null si no hay un fallo que comunicar.
     */
    public String errorCode() { return errorCode; }
    /**
     * Instante de creación del registro.
     *
     * @return Instante de creación del registro.
     */
    public Instant createdAt() { return createdAt; }
    /**
     * Instante del último cambio de estado guardado.
     *
     * @return Instante del último cambio de estado guardado.
     */
    public Instant updatedAt() { return updatedAt; }
    /**
     * Versión persistida para concurrencia optimista.
     *
     * @return Versión persistida para concurrencia optimista.
     */
    public long version() { return version; }

    /**
     * Usa el nombre sin espacios exteriores o el UUID de la aplicación cuando falta un nombre
     * visible.
     *
     * @param value Texto o número que se normaliza según el contrato del método.
     * @param appId UUID público de la aplicación del catálogo.
     * @return nombre no vacío para mostrar el elemento.
     */
    private static String normalizedName(String value, UUID appId) {
        return value == null || value.isBlank() ? appId.toString() : value.strip();
    }

    /**
     * Elimina espacios exteriores y representa ausencia de página mediante null.
     *
     * @param value Texto o número que se normaliza según el contrato del método.
     * @return texto recortado o null si estaba vacío.
     */
    private static String normalizedOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

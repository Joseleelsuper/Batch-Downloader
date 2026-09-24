package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transporta una instantánea del estado y los elementos para HTTP y eventos de actualización, sin
 * claves internas del almacén.
 *
 * @param id UUID estable del trabajo o elemento representado.
 * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
 * @param progress Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
 * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
 * @param acceptedCount Número de elementos realmente incluidos; coincide con el tamaño de items.
 * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
 * @param failureCode Código seguro del fallo global o null si no existe.
 * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
 * @param createdAt Instante de creación del registro.
 * @param expiresAt Instante límite de disponibilidad del ZIP.
 * @param artifactSizeBytes Tamaño del ZIP en bytes o null en eventos sin metadatos de integridad.
 * @param artifactSha256 SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
 * @param waitReason Código seguro del motivo temporal de espera, o null cuando no hay aplazamiento.
 * @param retryAt Instante previsto del siguiente intento por capacidad, o null cuando no
 *     corresponde.
 *
 * @param linux Contexto Linux o null para lotes sin destino Linux explícito.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJob
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public record DownloadJobView(
        UUID id,
        DownloadJobStatus status,
        int progress,
        int requestedCount,
        int acceptedCount,
        int omittedCount,
        String failureCode,
        List<Item> items,
        Instant createdAt,
        Instant expiresAt,
        Long artifactSizeBytes,
        String artifactSha256,
        String waitReason,
        Instant retryAt,
        LinuxContext linux,
        Long estimatedBytes,
        long reservedBytes,
        Long queuePosition,
        String deliveryStatus,
        long deliveryBytes) {

    public DownloadJobView(UUID id, DownloadJobStatus status, int progress, int requestedCount,
            int acceptedCount, int omittedCount, String failureCode, List<Item> items, Instant createdAt,
            Instant expiresAt, Long artifactSizeBytes, String artifactSha256, String waitReason,
            Instant retryAt, LinuxContext linux) {
        this(id, status, progress, requestedCount, acceptedCount, omittedCount, failureCode, items,
                createdAt, expiresAt, artifactSizeBytes, artifactSha256, waitReason, retryAt, linux,
                null, 0, null, "WAITING", 0);
    }

    public DownloadJobView withStorage(Long estimated, long reserved, Long position, String delivery, long bytes) {
        return new DownloadJobView(id, status, progress, requestedCount, acceptedCount, omittedCount,
                failureCode, items, createdAt, expiresAt, artifactSizeBytes, artifactSha256, waitReason,
                retryAt, linux, estimated, reserved, position, delivery, bytes);
    }

    /**
     * Conserva gestor, arquitectura y dependencias añadidas para que la recuperación del trabajo
     * mantenga el destino elegido.
     *
     * @param target Gestor Linux seleccionado o contexto validado del destino según la firma.
     * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
     * @param addedDependencyAppIds UUID añadidos automáticamente y ausentes de la selección
     *     original.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record LinuxContext(String target, String architecture, List<UUID> addedDependencyAppIds) {}

    /**
     * Construye una vista compatible con productores que todavía no aportan contexto Linux o
     * metadatos aditivos del ZIP.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param progress Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
     * @param acceptedCount Número de elementos realmente incluidos; coincide con el tamaño de
     *     items.
     *
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param failureCode Código seguro del fallo global o null si no existe.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @param createdAt Instante de creación del registro.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @param artifactSizeBytes Tamaño del ZIP en bytes o null en eventos sin metadatos de
     *     integridad.
     *
     * @param artifactSha256 SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     * @param waitReason Código seguro del motivo temporal de espera, o null cuando no hay
     *     aplazamiento.
     *
     * @param retryAt Instante previsto del siguiente intento por capacidad, o null cuando no
     *     corresponde.
     */
    public DownloadJobView(UUID id, DownloadJobStatus status, int progress, int requestedCount,
            int acceptedCount, int omittedCount, String failureCode, List<Item> items, Instant createdAt,
            Instant expiresAt, Long artifactSizeBytes, String artifactSha256, String waitReason, Instant retryAt) {
        this(id, status, progress, requestedCount, acceptedCount, omittedCount, failureCode, items,
                createdAt, expiresAt, artifactSizeBytes, artifactSha256, waitReason, retryAt, null);
    }

    /**
     * Crea otra instantánea con el contexto Linux indicado conservando todos los campos del estado.
     *
     * @param context Destino Linux y dependencias añadidas que se adjuntan a la vista del trabajo.
     * @return nueva vista con ese contexto, sin modificar esta instancia.
     */
    public DownloadJobView withLinuxContext(LinuxContext context) {
        return new DownloadJobView(id, status, progress, requestedCount, acceptedCount, omittedCount,
                failureCode, items, createdAt, expiresAt, artifactSizeBytes, artifactSha256, waitReason, retryAt,
                context, estimatedBytes, reservedBytes, queuePosition, deliveryStatus, deliveryBytes);
    }

    /**
     * Construye una vista compatible con productores que todavía no aportan contexto Linux o
     * metadatos aditivos del ZIP.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param progress Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
     * @param acceptedCount Número de elementos realmente incluidos; coincide con el tamaño de
     *     items.
     *
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param failureCode Código seguro del fallo global o null si no existe.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @param createdAt Instante de creación del registro.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     */
    public DownloadJobView(
            UUID id,
            DownloadJobStatus status,
            int progress,
            int requestedCount,
            int acceptedCount,
            int omittedCount,
            String failureCode,
            List<Item> items,
            Instant createdAt,
            Instant expiresAt) {
        this(id, status, progress, requestedCount, acceptedCount, omittedCount, failureCode,
                items, createdAt, expiresAt, null, null, null, null);
    }

    /**
     * Expone avance e integridad de un elemento admitido junto a su alternativa manual.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param appId UUID público de la aplicación del catálogo.
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
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    public record Item(
            UUID id,
            UUID appId,
            String appName,
            String officialPageUrl,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode) {}

    /**
     * Proyecta el agregado y sus elementos en orden de admisión sin exponer clave de objeto ni
     * hashes de propietario.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     * @return instantánea de estado; el caso de uso adjunta aparte el contexto Linux persistido.
     */
    public static DownloadJobView from(DownloadJob job) {
        return new DownloadJobView(
                job.id(),
                job.status(),
                job.progress(),
                job.requestedCount(),
                job.acceptedCount(),
                job.omittedCount(),
                job.failureCode(),
                job.items().stream().map(item -> new Item(
                        item.id(), item.appId(), item.appName(), item.officialPageUrl(),
                        item.status(), item.bytesDownloaded(), item.sha256(), item.errorCode()))
                        .toList(),
                job.createdAt(),
                job.expiresAt(),
                job.artifactSizeBytes(),
                job.artifactSha256(),
                job.waitReason(),
                job.retryAt());
    }
}

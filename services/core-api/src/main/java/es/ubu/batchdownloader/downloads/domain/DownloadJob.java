package es.ubu.batchdownloader.downloads.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mantiene las invariantes del lote, su único propietario y el avance de sus elementos hasta
 * publicar o retirar el ZIP.
 * Las transiciones conservan resultados terminales, integridad del artefacto y contadores
 * coherentes; los casos de uso se encargan de persistirlas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJobItem
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJobStatus
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public final class DownloadJob {
    /**
     * UUID estable del trabajo o elemento representado.
     */
    private final UUID id;
    /**
     * UUID de la cuenta propietaria o null para un trabajo anónimo.
     */
    private final UUID ownerId;
    /**
     * HMAC de la cookie anónima; null para trabajos de una cuenta.
     */
    private final String anonymousOwnerHash;
    /**
     * HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     */
    private final String anonymousIpHash;
    /**
     * Estado del trabajo o elemento correspondiente al evento o proyección.
     */
    private DownloadJobStatus status;
    /**
     * Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     */
    private int progress;
    /**
     * Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     */
    private String objectKey;
    /**
     * Tamaño del ZIP en bytes o null en eventos sin metadatos de integridad.
     */
    private Long artifactSizeBytes;
    /**
     * SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     */
    private String artifactSha256;
    /**
     * Código seguro del motivo temporal de espera, o null cuando no hay aplazamiento.
     */
    private String waitReason;
    /**
     * Instante previsto del siguiente intento por capacidad, o null cuando no corresponde.
     */
    private Instant retryAt;
    /**
     * Código seguro del fallo global o null si no existe.
     */
    private String failureCode;
    /**
     * Se ha solicitado cancelar y el agregado ya no admite progreso posterior.
     */
    private boolean cancellationRequested;
    /**
     * El propietario solicita aviso al terminar; la admisión lo habilita solo para cuentas
     * autenticadas.
     */
    private final boolean notifyWhenReady;
    /**
     * Cantidad seleccionada, incluidas dependencias Linux añadidas.
     */
    private final int requestedCount;
    /**
     * Número de elementos realmente incluidos; coincide con el tamaño de items.
     */
    private final int acceptedCount;
    /**
     * Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     */
    private final int omittedCount;
    /**
     * Instante de creación del registro.
     */
    private final Instant createdAt;
    /**
     * Instante del último cambio de estado guardado.
     */
    private Instant updatedAt;
    /**
     * Instante límite de disponibilidad del ZIP.
     */
    private Instant expiresAt;
    /**
     * Elementos en orden de admisión; el agregado conserva una copia de la lista.
     */
    private final List<DownloadJobItem> items;
    /**
     * Versión persistida para concurrencia optimista.
     */
    private long version;

    /**
     * Construye el agregado con una copia de la lista, porcentaje acotado y exactamente un
     * propietario autenticado o anónimo.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param progress Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param artifactSizeBytes Tamaño del ZIP en bytes o null en eventos sin metadatos de
     *     integridad.
     *
     * @param artifactSha256 SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     * @param waitReason Código seguro del motivo temporal de espera, o null cuando no hay
     *     aplazamiento.
     *
     * @param retryAt Instante previsto del siguiente intento por capacidad, o null cuando no
     *     corresponde.
     *
     * @param failureCode Código seguro del fallo global o null si no existe.
     * @param cancellationRequested Se ha solicitado cancelar y el agregado ya no admite progreso
     *     posterior.
     *
     * @param notifyWhenReady El propietario solicita aviso al terminar; la admisión lo habilita
     *     solo para cuentas autenticadas.
     *
     * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
     * @param acceptedCount Número de elementos realmente incluidos; coincide con el tamaño de
     *     items.
     *
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param createdAt Instante de creación del registro.
     * @param updatedAt Instante del último cambio de estado guardado.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @param version Versión persistida para concurrencia optimista.
     * @throws IllegalArgumentException si no hay un único propietario, faltan elementos o los
     *     contadores no cuadran.
     *
     * @throws NullPointerException si faltan identidad, estado, fechas o lista requeridos.
     */
    private DownloadJob(
            UUID id,
            UUID ownerId,
            String anonymousOwnerHash,
            String anonymousIpHash,
            DownloadJobStatus status,
            int progress,
            String objectKey,
            Long artifactSizeBytes,
            String artifactSha256,
            String waitReason,
            Instant retryAt,
            String failureCode,
            boolean cancellationRequested,
            boolean notifyWhenReady,
            int requestedCount,
            int acceptedCount,
            int omittedCount,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            List<DownloadJobItem> items,
            long version) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = ownerId;
        this.anonymousOwnerHash = anonymousOwnerHash;
        this.anonymousIpHash = anonymousIpHash;
        this.status = Objects.requireNonNull(status);
        this.progress = clampProgress(progress);
        this.objectKey = objectKey;
        this.artifactSizeBytes = artifactSizeBytes;
        this.artifactSha256 = artifactSha256;
        this.waitReason = waitReason;
        this.retryAt = retryAt;
        this.failureCode = failureCode;
        this.cancellationRequested = cancellationRequested;
        this.notifyWhenReady = notifyWhenReady;
        this.requestedCount = requestedCount;
        this.acceptedCount = acceptedCount;
        this.omittedCount = omittedCount;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.items = List.copyOf(items);
        this.version = version;
        if ((ownerId == null) == (anonymousOwnerHash == null || anonymousOwnerHash.isBlank())) {
            throw new IllegalArgumentException("download_job_requires_exactly_one_owner");
        }
        if (items.isEmpty()) throw new IllegalArgumentException("download_job_requires_items");
        if (requestedCount < acceptedCount || acceptedCount != items.size() || omittedCount < 0
                || requestedCount != acceptedCount + omittedCount) {
            throw new IllegalArgumentException("invalid_download_job_counts");
        }
    }

    /**
     * Crea un lote QUEUED con nueva identidad, progreso cero y versión inicial para los elementos
     * ya seleccionados.
     *
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param notifyWhenReady El propietario solicita aviso al terminar; la admisión lo habilita
     *     solo para cuentas autenticadas.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @return nuevo agregado todavía no persistido.
     */
    public static DownloadJob queue(
            UUID ownerId,
            String anonymousOwnerHash,
            String anonymousIpHash,
            List<DownloadJobItem> items,
            int requestedCount,
            int omittedCount,
            boolean notifyWhenReady,
            Instant now,
            Instant expiresAt) {
        return new DownloadJob(
                UUID.randomUUID(), ownerId, anonymousOwnerHash, anonymousIpHash,
                DownloadJobStatus.QUEUED, 0, null, null, null, null, null, null, false,
                notifyWhenReady, requestedCount, items.size(), omittedCount,
                now, now, expiresAt, items, 0);
    }

    /**
     * Restaura el estado persistido aplicando las mismas invariantes de propiedad, elementos y
     * contadores que una creación.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param progress Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param failureCode Código seguro del fallo global o null si no existe.
     * @param cancellationRequested Se ha solicitado cancelar y el agregado ya no admite progreso
     *     posterior.
     *
     * @param notifyWhenReady El propietario solicita aviso al terminar; la admisión lo habilita
     *     solo para cuentas autenticadas.
     *
     * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
     * @param acceptedCount Número de elementos realmente incluidos; coincide con el tamaño de
     *     items.
     *
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param createdAt Instante de creación del registro.
     * @param updatedAt Instante del último cambio de estado guardado.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @param version Versión persistida para concurrencia optimista.
     * @return agregado rehidratado; la variante histórica deja integridad y espera sin informar.
     */
    public static DownloadJob rehydrate(
            UUID id, UUID ownerId, String anonymousOwnerHash, String anonymousIpHash,
            DownloadJobStatus status, int progress, String objectKey, String failureCode,
            boolean cancellationRequested, boolean notifyWhenReady,
            int requestedCount, int acceptedCount, int omittedCount,
            Instant createdAt, Instant updatedAt, Instant expiresAt, List<DownloadJobItem> items, long version) {
        return new DownloadJob(
                id, ownerId, anonymousOwnerHash, anonymousIpHash, status, progress, objectKey,
                null, null, null, null, failureCode,
                cancellationRequested, notifyWhenReady, requestedCount, acceptedCount, omittedCount,
                createdAt, updatedAt, expiresAt, items, version);
    }

    /**
     * Restaura el estado persistido aplicando las mismas invariantes de propiedad, elementos y
     * contadores que una creación.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param progress Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param artifactSizeBytes Tamaño del ZIP en bytes o null en eventos sin metadatos de
     *     integridad.
     *
     * @param artifactSha256 SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     * @param waitReason Código seguro del motivo temporal de espera, o null cuando no hay
     *     aplazamiento.
     *
     * @param retryAt Instante previsto del siguiente intento por capacidad, o null cuando no
     *     corresponde.
     *
     * @param failureCode Código seguro del fallo global o null si no existe.
     * @param cancellationRequested Se ha solicitado cancelar y el agregado ya no admite progreso
     *     posterior.
     *
     * @param notifyWhenReady El propietario solicita aviso al terminar; la admisión lo habilita
     *     solo para cuentas autenticadas.
     *
     * @param requestedCount Cantidad seleccionada, incluidas dependencias Linux añadidas.
     * @param acceptedCount Número de elementos realmente incluidos; coincide con el tamaño de
     *     items.
     *
     * @param omittedCount Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     * @param createdAt Instante de creación del registro.
     * @param updatedAt Instante del último cambio de estado guardado.
     * @param expiresAt Instante límite de disponibilidad del ZIP.
     * @param items Elementos en orden de admisión; el agregado conserva una copia de la lista.
     * @param version Versión persistida para concurrencia optimista.
     * @return agregado rehidratado; la variante histórica deja integridad y espera sin informar.
     */
    public static DownloadJob rehydrate(
            UUID id, UUID ownerId, String anonymousOwnerHash, String anonymousIpHash,
            DownloadJobStatus status, int progress, String objectKey,
            Long artifactSizeBytes, String artifactSha256, String waitReason, Instant retryAt,
            String failureCode, boolean cancellationRequested, boolean notifyWhenReady,
            int requestedCount, int acceptedCount, int omittedCount,
            Instant createdAt, Instant updatedAt, Instant expiresAt,
            List<DownloadJobItem> items, long version) {
        return new DownloadJob(
                id, ownerId, anonymousOwnerHash, anonymousIpHash, status, progress, objectKey,
                artifactSizeBytes, artifactSha256, waitReason, retryAt, failureCode,
                cancellationRequested, notifyWhenReady, requestedCount, acceptedCount, omittedCount,
                createdAt, updatedAt, expiresAt, items, version);
    }

    /**
     * Aplica progreso a un elemento de un trabajo no terminal y avanza el lote sin retroceder de
     * fase.
     * Los elementos terminales aportan hasta el 90% y al terminar todos comienza PACKAGING.
     *
     * @param itemId UUID de un elemento perteneciente al trabajo indicado.
     * @param itemStatus Estado del elemento comunicado por el worker.
     * @param bytesDownloaded Bytes transferidos del instalador; el dominio conserva el máximo
     *     recibido.
     *
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     *
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @throws IllegalArgumentException si el UUID del elemento no pertenece al trabajo.
     */
    public void updateItem(
            UUID itemId, DownloadItemStatus itemStatus, long bytesDownloaded, String sha256,
            String errorCode, Instant now) {
        if (status.terminal()) return;
        waitReason = null;
        retryAt = null;
        DownloadJobItem item = items.stream().filter(candidate -> candidate.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown_download_item"));
        item.progress(itemStatus, bytesDownloaded, sha256, errorCode, now);
        long terminalItems = items.stream().filter(candidate -> candidate.status().terminal()).count();
        progress = Math.max(progress, (int) ((terminalItems * 90L) / items.size()));
        DownloadJobStatus next = terminalItems == items.size()
                ? DownloadJobStatus.PACKAGING
                : deriveActiveStatus(itemStatus);
        if (activeStage(next) >= activeStage(status)) {
            status = next;
        }
        updatedAt = now;
    }

    /**
     * Publica un resultado descargable con progreso completo, clave e integridad del ZIP y borra el
     * aplazamiento.
     * Una cancelación o expiración previa impide reabrir el trabajo.
     *
     * @param result Estado final descargable: READY, PARTIAL o MANUAL_ONLY.
     * @param key Clave no vacía del ZIP publicado en el almacén.
     * @param workerExpiry Vencimiento efectivo acordado con el worker y limitado por la aplicación.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @throws IllegalArgumentException si el resultado no es descargable, la clave está vacía, el
     *     tamaño es negativo o el SHA-256 es inválido.
     */
    public void markReady(DownloadJobStatus result, String key, Instant workerExpiry, Instant now) {
        markReady(result, key, null, null, workerExpiry, now);
    }

    /**
     * Publica un resultado descargable con progreso completo, clave e integridad del ZIP y borra el
     * aplazamiento.
     * Una cancelación o expiración previa impide reabrir el trabajo.
     *
     * @param result Estado final descargable: READY, PARTIAL o MANUAL_ONLY.
     * @param key Clave no vacía del ZIP publicado en el almacén.
     * @param sizeBytes Tamaño no negativo del ZIP o null si no se conoce.
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     *
     * @param workerExpiry Vencimiento efectivo acordado con el worker y limitado por la aplicación.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @throws IllegalArgumentException si el resultado no es descargable, la clave está vacía, el
     *     tamaño es negativo o el SHA-256 es inválido.
     */
    public void markReady(
            DownloadJobStatus result,
            String key,
            Long sizeBytes,
            String sha256,
            Instant workerExpiry,
            Instant now) {
        if (status == DownloadJobStatus.CANCELLED || status == DownloadJobStatus.EXPIRED) return;
        if (!result.downloadable()) throw new IllegalArgumentException("invalid_download_result_status");
        if (sizeBytes != null && sizeBytes < 0) throw new IllegalArgumentException("invalid_artifact_size");
        if (sha256 != null && !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid_artifact_sha256");
        }
        objectKey = requireText(key, "objectKey");
        artifactSizeBytes = sizeBytes;
        artifactSha256 = sha256 == null ? null : sha256.toLowerCase(java.util.Locale.ROOT);
        waitReason = null;
        retryAt = null;
        status = result;
        progress = 100;
        expiresAt = Objects.requireNonNull(workerExpiry);
        updatedAt = now;
    }

    /**
     * Reinicia a QUEUED un trabajo no terminal, reencola sus elementos y conserva motivo y fecha
     * para el siguiente intento por capacidad.
     *
     * @param reason Código no vacío de la falta temporal de capacidad.
     * @param nextAttempt Instante no nulo del siguiente intento solicitado por el worker.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     */
    public void defer(String reason, Instant nextAttempt, Instant now) {
        if (status.terminal()) return;
        waitReason = requireText(reason, "waitReason");
        retryAt = Objects.requireNonNull(nextAttempt);
        status = DownloadJobStatus.QUEUED;
        progress = 0;
        items.forEach(item -> item.requeue(now));
        updatedAt = now;
    }

    /**
     * Marca FAILED un trabajo no terminal con un código seguro no vacío; los resultados ya
     * terminales se conservan.
     *
     * @param code Código no vacío del fallo global del trabajo.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     */
    public void fail(String code, Instant now) {
        if (status.terminal()) return;
        failureCode = requireText(code, "failureCode");
        status = DownloadJobStatus.FAILED;
        updatedAt = now;
    }

    /**
     * Marca el lote CANCELLED y cancela sus elementos todavía activos conservando los resultados
     * individuales ya terminales.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return true solo si se aplicó una cancelación nueva.
     */
    public boolean requestCancellation(Instant now) {
        if (status.terminal()) return false;
        cancellationRequested = true;
        status = DownloadJobStatus.CANCELLED;
        items.forEach(item -> item.cancel(now));
        updatedAt = now;
        return true;
    }

    /**
     * Retira la clave del ZIP al vencer un resultado descargable, conservando el historial del
     * trabajo.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return true si el estado cambia a EXPIRED; false si no ha vencido o no era descargable.
     */
    public boolean expire(Instant now) {
        if (!expiresAt.isAfter(now) && status.downloadable()) {
            status = DownloadJobStatus.EXPIRED;
            objectKey = null;
            updatedAt = now;
            return true;
        }
        return false;
    }

    /**
     * Traduce la fase del elemento al avance global antes de que todos terminen y comience el
     * empaquetado.
     *
     * @param itemStatus Estado del elemento comunicado por el worker.
     * @return QUEUED, RESOLVING o DOWNLOADING según el estado recibido.
     */
    private static DownloadJobStatus deriveActiveStatus(DownloadItemStatus itemStatus) {
        return switch (itemStatus) {
            case QUEUED -> DownloadJobStatus.QUEUED;
            case RESOLVING -> DownloadJobStatus.RESOLVING;
            case DOWNLOADING, COMPLETED, FAILED, CANCELLED -> DownloadJobStatus.DOWNLOADING;
        };
    }

    /**
     * Asigna un orden de avance a las fases del lote para impedir retrocesos por eventos tardíos.
     *
     * @param candidate Estado cuyo orden de avance se necesita comparar.
     * @return ordinal funcional entre cero y cuatro, con el mismo nivel para resultados terminales.
     */
    private static int activeStage(DownloadJobStatus candidate) {
        return switch (candidate) {
            case QUEUED -> 0;
            case RESOLVING -> 1;
            case DOWNLOADING -> 2;
            case PACKAGING -> 3;
            case READY, PARTIAL, MANUAL_ONLY, FAILED, CANCELLED, EXPIRED -> 4;
        };
    }

    /**
     * Acota el porcentaje rehidratado al intervalo visible del progreso.
     *
     * @param value Porcentaje recibido o persistido antes de normalizar.
     * @return valor entre 0 y 100.
     */
    private static int clampProgress(int value) { return Math.max(0, Math.min(100, value)); }
    /**
     * Exige texto no vacío en claves y códigos del estado sin modificar su contenido.
     *
     * @param value Texto o número que se normaliza según el contrato del método.
     * @param name Nombre del campo que se incluye en el error de validación.
     * @return texto original.
     * @throws IllegalArgumentException si es null o contiene solo espacios.
     */
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    /**
     * UUID estable del trabajo o elemento representado.
     *
     * @return UUID estable del trabajo o elemento representado.
     */
    public UUID id() { return id; }
    /**
     * UUID de la cuenta propietaria o null para un trabajo anónimo.
     *
     * @return UUID de la cuenta propietaria o null para un trabajo anónimo.
     */
    public UUID ownerId() { return ownerId; }
    /**
     * HMAC de la cookie anónima; null para trabajos de una cuenta.
     *
     * @return HMAC de la cookie anónima; null para trabajos de una cuenta.
     */
    public String anonymousOwnerHash() { return anonymousOwnerHash; }
    /**
     * HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     *
     * @return HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     */
    public String anonymousIpHash() { return anonymousIpHash; }
    /**
     * Estado del trabajo o elemento correspondiente al evento o proyección.
     *
     * @return Estado del trabajo o elemento correspondiente al evento o proyección.
     */
    public DownloadJobStatus status() { return status; }
    /**
     * Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     *
     * @return Porcentaje entre 0 y 100; el empaquetado completa el tramo final del trabajo.
     */
    public int progress() { return progress; }
    /**
     * Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     *
     * @return Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     */
    public String objectKey() { return objectKey; }
    /**
     * Tamaño del ZIP en bytes o null en eventos sin metadatos de integridad.
     *
     * @return Tamaño del ZIP en bytes o null en eventos sin metadatos de integridad.
     */
    public Long artifactSizeBytes() { return artifactSizeBytes; }
    /**
     * SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     *
     * @return SHA-256 hexadecimal del ZIP o null si el productor no lo proporciona.
     */
    public String artifactSha256() { return artifactSha256; }
    /**
     * Código seguro del motivo temporal de espera, o null cuando no hay aplazamiento.
     *
     * @return Código seguro del motivo temporal de espera, o null cuando no hay aplazamiento.
     */
    public String waitReason() { return waitReason; }
    /**
     * Instante previsto del siguiente intento por capacidad, o null cuando no corresponde.
     *
     * @return Instante previsto del siguiente intento por capacidad, o null cuando no corresponde.
     */
    public Instant retryAt() { return retryAt; }
    /**
     * Código seguro del fallo global o null si no existe.
     *
     * @return Código seguro del fallo global o null si no existe.
     */
    public String failureCode() { return failureCode; }
    /**
     * Se ha solicitado cancelar y el agregado ya no admite progreso posterior.
     *
     * @return Se ha solicitado cancelar y el agregado ya no admite progreso posterior.
     */
    public boolean cancellationRequested() { return cancellationRequested; }
    /**
     * El propietario solicita aviso al terminar; la admisión lo habilita solo para cuentas
     * autenticadas.
     *
     * @return El propietario solicita aviso al terminar; la admisión lo habilita solo para cuentas
     *     autenticadas.
     */
    public boolean notifyWhenReady() { return notifyWhenReady; }
    /**
     * Cantidad seleccionada, incluidas dependencias Linux añadidas.
     *
     * @return Cantidad seleccionada, incluidas dependencias Linux añadidas.
     */
    public int requestedCount() { return requestedCount; }
    /**
     * Número de elementos realmente incluidos; coincide con el tamaño de items.
     *
     * @return Número de elementos realmente incluidos; coincide con el tamaño de items.
     */
    public int acceptedCount() { return acceptedCount; }
    /**
     * Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     *
     * @return Aplicaciones solicitadas sin instalador ni alternativa manual aceptada.
     */
    public int omittedCount() { return omittedCount; }
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
     * Instante límite de disponibilidad del ZIP.
     *
     * @return Instante límite de disponibilidad del ZIP.
     */
    public Instant expiresAt() { return expiresAt; }
    /**
     * Elementos en orden de admisión; el agregado conserva una copia de la lista.
     *
     * @return Elementos en orden de admisión; el agregado conserva una copia de la lista.
     */
    public List<DownloadJobItem> items() { return items; }
    /**
     * Versión persistida para concurrencia optimista.
     *
     * @return Versión persistida para concurrencia optimista.
     */
    public long version() { return version; }
}

package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Define el contrato de {@code DownloadJobStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface DownloadJobStore {
    /**
     * Serializa la comprobación de límites y la inserción dentro de la transacción actual.
     */
    void lockAdmission();

    /**
     * Guarda el recurso solicitado mediante {@code save}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     * @return Resultado producido por {@code save}.
     */
    DownloadJob save(DownloadJob job);
    /**
     * Busca el resultado solicitado mediante {@code findById}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @return Resultado producido por {@code findById}.
     */
    Optional<DownloadJob> findById(UUID id);
    /**
     * Busca el resultado solicitado mediante {@code findDownloadableExpiredBefore}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    List<DownloadJob> findDownloadableExpiredBefore(Instant now);
    /**
     * Ejecuta la operación {@code countAnonymousNonTerminal}.
     *
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    long countAnonymousNonTerminal(String anonymousOwnerHash);
    /**
     * Ejecuta la operación {@code countAnonymousCreatedSince}.
     *
     * @param anonymousOwnerHash Valor de {@code anonymousOwnerHash} utilizado por la operación.
     * @param createdAfter Valor de {@code createdAfter} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    long countAnonymousCreatedSince(String anonymousOwnerHash, Instant createdAfter);
    /**
     * Ejecuta la operación {@code countAnonymousIpCreatedSince}.
     *
     * @param anonymousIpHash Valor de {@code anonymousIpHash} utilizado por la operación.
     * @param createdAfter Valor de {@code createdAfter} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    long countAnonymousIpCreatedSince(String anonymousIpHash, Instant createdAfter);
    /**
     * Cuenta todos los trabajos que todavía consumen capacidad de cola.
     *
     * @return Número de trabajos no terminales.
     */
    long countNonTerminal();
    /**
     * Cuenta los trabajos activos o pendientes de una cuenta.
     *
     * @param ownerId Identificador de la cuenta.
     * @return Número de trabajos no terminales de la cuenta.
     */
    long countNonTerminalByOwner(UUID ownerId);
    /**
     * Actualiza un único item y el agregado del trabajo sin reescribir todo el grafo.
     *
     * @return Trabajo actualizado, si existe.
     */
    Optional<DownloadJob> applyProgress(
            UUID jobId,
            UUID itemId,
            DownloadItemStatus status,
            long bytesDownloaded,
            String sha256,
            String errorCode,
            Instant now);
}

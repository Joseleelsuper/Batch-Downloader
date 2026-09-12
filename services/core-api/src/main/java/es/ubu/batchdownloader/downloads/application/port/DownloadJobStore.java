package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persiste el agregado de descarga y proporciona las consultas y el bloqueo que mantienen atómica
 * su admisión.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobEventHandler
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJob
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public interface DownloadJobStore {
    /**
     * Permite guardar destino y dependencias en la transacción del trabajo; el método por defecto
     * no conserva contexto.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param context Destino Linux y dependencias añadidas que se adjuntan a la vista del trabajo.
     */
    default void saveLinuxContext(UUID jobId,
            es.ubu.batchdownloader.downloads.application.DownloadJobView.LinuxContext context) {}

    /**
     * Recupera el destino Linux guardado; la implementación por defecto representa la ausencia de
     * soporte de contexto.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @return contexto persistido o null cuando no está disponible.
     */
    default es.ubu.batchdownloader.downloads.application.DownloadJobView.LinuxContext linuxContext(UUID jobId) {
        return null;
    }
    /**
     * Serializa la comprobación de cuotas y la inserción de trabajos dentro de la transacción del
     * llamador.
     */
    void lockAdmission();

    /**
     * Inserta o actualiza el trabajo y sus elementos en la transacción vigente.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     * @return agregado persistido, incluida su versión para concurrencia optimista.
     */
    DownloadJob save(DownloadJob job);
    /**
     * Recupera un agregado con sus elementos sin comprobar la identidad del solicitante.
     *
     * @param id UUID estable del trabajo o elemento representado.
     * @return trabajo conocido o vacío si el UUID no existe.
     * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
     */
    Optional<DownloadJob> findById(UUID id);
    /**
     * Localiza trabajos con resultado descargable cuyo vencimiento es anterior o igual al instante
     * indicado.
     *
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return trabajos READY, PARTIAL o MANUAL_ONLY pendientes de expirar.
     */
    List<DownloadJob> findDownloadableExpiredBefore(Instant now);
    /**
     * Cuenta los trabajos del navegador que todavía consumen su cuota de actividad.
     *
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @return número de trabajos no terminales del hash de propietario.
     */
    long countAnonymousNonTerminal(String anonymousOwnerHash);
    /**
     * Cuenta todas las creaciones del navegador desde el límite incluido, aunque ya hayan
     * terminado.
     *
     * @param anonymousOwnerHash HMAC de la cookie anónima; null para trabajos de una cuenta.
     * @param createdAfter Inicio incluido de la ventana de creación.
     * @return creaciones que consumen la cuota temporal del navegador.
     */
    long countAnonymousCreatedSince(String anonymousOwnerHash, Instant createdAfter);
    /**
     * Cuenta las creaciones anónimas asociadas al hash de IP desde el límite incluido.
     *
     * @param anonymousIpHash HMAC de la dirección IP para cuotas; null si no se dispone de ella.
     * @param createdAfter Inicio incluido de la ventana de creación.
     * @return creaciones que consumen la cuota temporal de esa dirección.
     */
    long countAnonymousIpCreatedSince(String anonymousIpHash, Instant createdAfter);
    /**
     * Cuenta los trabajos que todavía ocupan capacidad global de procesamiento.
     *
     * @return total de trabajos no terminales.
     */
    long countNonTerminal();
    /**
     * Cuenta los trabajos pendientes o activos de una cuenta autenticada.
     *
     * @param ownerId UUID de la cuenta propietaria o null para un trabajo anónimo.
     * @return trabajos no terminales de la cuenta.
     */
    long countNonTerminalByOwner(UUID ownerId);
    /**
     * Actualiza el elemento y recalcula el progreso del trabajo sin reescribir todo el agregado ni
     * reabrir estados terminales.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param itemId UUID de un elemento perteneciente al trabajo indicado.
     * @param status Estado del trabajo o elemento correspondiente al evento o proyección.
     * @param bytesDownloaded Bytes transferidos del instalador; el dominio conserva el máximo
     *     recibido.
     * @param sha256 SHA-256 hexadecimal del contenido cuando se conoce; null si todavía no está
     *     disponible.
     * @param errorCode Código seguro del fallo del elemento o null si no hay un fallo que
     *     comunicar.
     * @param now Instante de la transición o consulta de cuotas obtenido del reloj del caso de uso.
     * @return trabajo resultante o vacío si no existe; los eventos tardíos pueden dejarlo intacto.
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

package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;

/**
 * Mide cada llamada de descarga y distingue éxito, rechazo funcional y otros fallos sin cambiar su
 * resultado.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class MeteredRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;
    private final MeterRegistry registry;

    /**
     * Conecta la siguiente política y el registro de duración por resultado.
     *
     * @param delegate Siguiente política o transporte de la cadena de descarga.
     * @param registry Registro de duraciones, concurrencia y reintentos.
     */
    public MeteredRemoteDownloader(RemoteDownloader delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Registra duración en la salida normal o excepcional y propaga el mismo artefacto o fallo de
     * la llamada delegada.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto de la transferencia delegada.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            return delegate.download(item, filename, target, totalBudget, maxFileBytes);
        } catch (DownloadRejectedException exception) {
            outcome = "rejected";
            throw exception;
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder("download_worker_remote_download")
                    .tag("outcome", outcome)
                    .register(registry));
        }
    }
}

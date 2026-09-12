package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.RemoteExchange;
import java.net.http.HttpClient;
import java.nio.file.Path;

/**
 * Ofrece una composición de transporte JDK con validación pública de URI, límites, integridad y
 * limpieza de archivos fallidos.
 *
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsRemoteExchange
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.DefaultRemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.IntegrityCheckingRemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public class JdkHttpsRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;

    /**
     * Compone las políticas HTTP, integridad y limpieza para el cliente y los límites recibidos.
     *
     * @param client Cliente HTTP JDK que proporciona conexiones y respuestas con cuerpo en
     *     streaming.
     * @param uriPolicy Política que comprueba HTTPS, credenciales y direcciones de cada destino.
     * @param properties Límites de tamaño, tiempo y redirecciones configurados para la
     *     transferencia.
     */
    public JdkHttpsRemoteDownloader(
            HttpClient client,
            PublicHttpsUriPolicy uriPolicy,
            DownloadProperties properties) {
        RemoteExchange exchange = new PublicHttpsRemoteExchange(
                new JdkRemoteExchange(client, properties),
                uriPolicy);
        this.delegate = new PartialFileCleanupRemoteDownloader(
                new IntegrityCheckingRemoteDownloader(
                        new DefaultRemoteDownloader(exchange, properties)));
    }

    /**
     * Delega la transferencia en la cadena configurada de validación, límites, integridad y
     * limpieza.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto local completo que superó sus políticas.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        return delegate.download(item, filename, target, totalBudget, maxFileBytes);
    }
}

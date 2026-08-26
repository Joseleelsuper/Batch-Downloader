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
 * Fachada compatible que compone la descarga JDK con seguridad, integridad y cleanup.
 *
 * <p>Se conserva el nombre público para no romper consumidores ni pruebas existentes. La
 * configuración de producción puede envolver esta fachada con observabilidad adicional.</p>
 */
public class JdkHttpsRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;

    /** Inicializa la composición HTTP segura sobre el cliente JDK. */
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

    /** {@inheritDoc} */
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

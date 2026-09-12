package es.ubu.batchdownloader.downloadworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadManifest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ManifestItem;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Genera el manifiesto del ZIP con resultados individuales en el orden original de admisión y
 * referencias exactas, sin exponer las URI finales de los proveedores.
 *
 * @see es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadManifest
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Resultados y empaquetado
 */
public final class DownloadManifestWriter {
    private static final int MANIFEST_VERSION = 3;

    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Conecta serialización y reloj del manifiesto entregable.
     *
     * @param objectMapper Serializador del manifiesto JSON del trabajo.
     * @param clock Reloj para fechar el progreso y las decisiones del coordinador.
     */
    public DownloadManifestWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Combina instaladores íntegros, rechazos y rutas manuales por elemento y reconstruye el orden
     * de la solicitud antes de serializar.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param status Resultado conjunto READY, PARTIAL o MANUAL_ONLY que se incluirá en el
     *     manifiesto.
     * @param downloaded Instaladores descargados y verificados que se copiarán al ZIP.
     * @param failed Elementos que no terminaron con un instalador descargado.
     * @param failedMetadata Nombre y página oficial de elementos que fallaron, por UUID de
     *     elemento.
     * @param manualShortcutPaths Ubicación de las entradas manuales por UUID de elemento.
     * @return manifiesto JSON formateado en bytes.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no puede
     *     serializarse el manifiesto.
     */
    byte[] write(
            DownloadJobRequestedEvent event,
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            Map<UUID, DownloadItemMetadata> failedMetadata,
            Map<UUID, String> manualShortcutPaths) {
        Map<UUID, ManifestItem> items = new HashMap<>();
        for (DownloadedArtifact artifact : downloaded) {
            var installation = artifact.installation();
            items.put(artifact.itemId(), new ManifestItem(
                    artifact.itemId(), artifact.appId(), artifact.sourceRef(),
                    installation == null ? null : installation.appName(),
                    artifact.filename(), "COMPLETED", artifact.sizeBytes(), artifact.sha256(),
                    artifact.filename(), null, null, null,
                    installation == null ? null : installation.operatingSystem(),
                    installation == null ? null : installation.architecture(),
                    installation == null ? null : installation.version(),
                    installation == null ? null : LinuxInstallerBundleWriter.support(installation)));
        }
        for (FailedDownload failure : failed) {
            DownloadItemMetadata metadata = failedMetadata.get(failure.itemId());
            String shortcut = manualShortcutPaths.get(failure.itemId());
            items.put(failure.itemId(), new ManifestItem(
                    failure.itemId(), failure.appId(), failure.sourceRef(),
                    metadata == null ? failure.appId().toString() : metadata.appName(),
                    failure.filename(), "FAILED", null, null,
                    shortcut, null, failure.errorCode(), shortcut));
        }
        List<ManifestItem> ordered = event.payload().items().stream()
                .map(item -> items.get(item.itemId()))
                .toList();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new DownloadManifest(
                    MANIFEST_VERSION, event.payload().jobId(), clock.instant(), status, ordered));
        } catch (IOException exception) {
            throw new InfrastructureException("manifest_creation_failed", exception);
        }
    }
}

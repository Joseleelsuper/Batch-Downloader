package es.ubu.batchdownloader.downloads.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mantiene juntas las decisiones del usuario al cruzar HTTP, admisión y previsualización Linux;
 * conserva la fuente exacta opcional.
 *
 * @param appIds Selección de UUID de aplicaciones en el orden solicitado.
 * @param operatingSystems Plataformas admitidas con semántica OR; la consulta conserva su política
 *     de selección.
 *
 * @param sourceRef UUID de la fuente exacta; null permite selección automática o representa una
 *     alternativa manual.
 *
 * @param linuxTarget Gestor Linux opcional; junto con arquitectura define un destino explícito.
 * @param targetArchitecture Arquitectura Linux opcional que acompaña al gestor explícito.
 * @param sourceSelections Fuentes exactas elegidas para aplicaciones concretas del bundle.
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.LinuxTarget
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public record DownloadSelection(
        List<UUID> appIds, List<String> operatingSystems, UUID sourceRef,
        String linuxTarget, String targetArchitecture, Map<UUID, UUID> sourceSelections, String bundleId) {
    public DownloadSelection(List<UUID> appIds, List<String> operatingSystems, UUID sourceRef,
            String linuxTarget, String targetArchitecture, Map<UUID, UUID> sourceSelections) {
        this(appIds, operatingSystems, sourceRef, linuxTarget, targetArchitecture, sourceSelections, null);
    }
    public DownloadSelection(List<UUID> appIds, List<String> operatingSystems, UUID sourceRef,
            String linuxTarget, String targetArchitecture) {
        this(appIds, operatingSystems, sourceRef, linuxTarget, targetArchitecture, Map.of());
    }

    public DownloadSelection {
        sourceSelections = sourceSelections == null ? Map.of() : Map.copyOf(sourceSelections);
    }
}

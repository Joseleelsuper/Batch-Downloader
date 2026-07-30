package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import java.nio.file.Path;
import java.util.List;

/**
 * Define el contrato de {@code ArchiveBuilder}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface ArchiveBuilder {
    /**
     * Construye el resultado solicitado mediante {@code build}.
     *
     * @param target Valor de {@code target} utilizado por la operación.
     * @param artifacts Valor de {@code artifacts} utilizado por la operación.
     * @param supplementalEntries Valor de {@code supplementalEntries} utilizado por la operación.
     * @param manifest Valor de {@code manifest} utilizado por la operación.
     */
    void build(
            Path target,
            List<DownloadedArtifact> artifacts,
            List<ArchiveEntry> supplementalEntries,
            Path manifest);
}

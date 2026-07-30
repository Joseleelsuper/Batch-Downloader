package es.ubu.batchdownloader.downloadworker.infrastructure.archive;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Implementa el componente {@code ZipArchiveBuilder}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class ZipArchiveBuilder implements ArchiveBuilder {
    /**
     * Construye el resultado solicitado mediante {@code build}.
     *
     * @param target Valor de {@code target} utilizado por la operación.
     * @param artifacts Valor de {@code artifacts} utilizado por la operación.
     * @param supplementalEntries Valor de {@code supplementalEntries} utilizado por la operación.
     * @param manifest Valor de {@code manifest} utilizado por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Override
    public void build(
            Path target,
            List<DownloadedArtifact> artifacts,
            List<ArchiveEntry> supplementalEntries,
            Path manifest) {
        try {
            Files.createDirectories(target.getParent());
            try (OutputStream output = Files.newOutputStream(target);
                    ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (DownloadedArtifact artifact : artifacts) {
                    add(zip, artifact.filename(), artifact.path());
                }
                for (ArchiveEntry entry : supplementalEntries) {
                    add(zip, safeEntryName(entry.path()), entry.source());
                }
                add(zip, "manifest.json", manifest);
            }
        } catch (IOException exception) {
            throw new InfrastructureException("zip_creation_failed", exception);
        }
    }

    /**
     * Ejecuta la operación {@code safeEntryName}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code safeEntryName}.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private String safeEntryName(String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("/")
                || value.startsWith("\\")
                || value.contains("\\")
                || java.util.Arrays.asList(value.split("/")).contains("..")) {
            throw new InfrastructureException(
                    "invalid_zip_entry",
                    new IllegalArgumentException("Unsafe supplemental ZIP path"));
        }
        return value;
    }

    /**
     * Ejecuta la operación {@code add}.
     *
     * @param zip Valor de {@code zip} utilizado por la operación.
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @param source Fuente de descarga sobre la que se actúa.
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    private void add(ZipOutputStream zip, String filename, Path source) throws IOException {
        zip.putNextEntry(new ZipEntry(filename));
        try (InputStream input = Files.newInputStream(source)) {
            input.transferTo(zip);
        }
        zip.closeEntry();
    }
}

package es.ubu.batchdownloader.downloadworker.infrastructure.archive;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.Zip64Mode;

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
    public void build(OutputStream target, int compressionLevel, ArchiveContents contents) {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(target)) {
            zip.setEncoding("UTF-8");
            zip.setUseZip64(Zip64Mode.AsNeeded);
            zip.setLevel(Math.clamp(compressionLevel, 0, 9));
            contents.write(new ArchiveWriter() {
                /** {@inheritDoc} */
                @Override
                public void add(String path, Path source) throws IOException {
                    ZipArchiveBuilder.this.add(zip, safeEntryName(path), source);
                }

                /** {@inheritDoc} */
                @Override
                public void add(String path, byte[] content) throws IOException {
                    zip.putArchiveEntry(entry(safeEntryName(path), false));
                    zip.write(content);
                    zip.closeArchiveEntry();
                }

                @Override
                public void addExecutable(String path, byte[] content) throws IOException {
                    zip.putArchiveEntry(entry(safeEntryName(path), true));
                    zip.write(content);
                    zip.closeArchiveEntry();
                }
            });
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
    private static ZipArchiveEntry entry(String name, boolean executable) {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(executable ? 0100755 : 0100644);
        return entry;
    }

    private void add(ZipArchiveOutputStream zip, String filename, Path source) throws IOException {
        zip.putArchiveEntry(entry(filename, false));
        try (InputStream input = Files.newInputStream(source)) {
            input.transferTo(zip);
        }
        zip.closeArchiveEntry();
    }
}

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
 * Construye ZIP UTF-8 con Zip64 cuando hace falta y copia entradas por streaming. Valida nombres
 * relativos y asigna permisos UNIX a los scripts ejecutables del runtime.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder
 * @since 0.1.0
 * @version 0.1.0
 * @category Construcción de archivos
 */
public class ZipArchiveBuilder implements ArchiveBuilder {
    /**
     * Abre el ZIP con compresión acotada, permite producir las entradas y cierra el archivo y su
     * flujo de destino incluso si falla la escritura.
     *
     * @param target Flujo de salida del archivo; la implementación ZIP lo cierra al terminar o
     *     fallar.
     * @param compressionLevel Nivel de compresión solicitado; ZIP lo acota entre cero y nueve.
     * @param contents Productor que añade entradas mientras el archivo permanece abierto.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si hay un
     *     error de E/S al crear el ZIP o un nombre de entrada inseguro.
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

                /**
                 * Valida la ruta y escribe una entrada regular con permisos Unix 0755 para permitir
                 * ejecutar el lanzador tras extraer el ZIP.
                 *
                 * @param path nombre relativo de la entrada ZIP, sujeto a validación.
                 * @param content bytes que se incorporan a la entrada ejecutable.
                 * @throws java.io.IOException si falla la escritura o el cierre de la entrada.
                 */
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
     * Rechaza nombres ausentes, rutas absolutas, barras inversas y segmentos .. antes de crear una
     * entrada ZIP.
     *
     * @param value Nombre de entrada que se valida antes de escribir en el ZIP.
     * @return nombre relativo original, sin normalizar ni cambiar sus caracteres.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si el
     *     nombre no supera las restricciones, con código invalid_zip_entry.
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
     * Crea la cabecera ZIP con tipo de archivo regular y permisos UNIX 0755 o 0644 según el uso de
     * la entrada.
     *
     * @param name Nombre relativo ya validado para la cabecera de la entrada.
     * @param executable true fija permisos UNIX 0755; false utiliza 0644.
     * @return entrada todavía no escrita en el archivo.
     */
    private static ZipArchiveEntry entry(String name, boolean executable) {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setUnixMode(executable ? 0100755 : 0100644);
        return entry;
    }

    /**
     * Abre la entrada, transfiere el archivo local por streaming y cierra su lectura y la entrada
     * antes de continuar.
     *
     * @param zip Archivo ZIP abierto al que se añade la entrada.
     * @param filename Nombre relativo ya validado de la entrada que recibe el archivo local.
     * @param source Archivo local que se lee por streaming, sin cargarlo completo en memoria.
     * @throws java.io.IOException si falla la lectura local, la copia o el cierre de la entrada.
     */
    private void add(ZipArchiveOutputStream zip, String filename, Path source) throws IOException {
        zip.putArchiveEntry(entry(filename, false));
        try (InputStream input = Files.newInputStream(source)) {
            input.transferTo(zip);
        }
        zip.closeArchiveEntry();
    }
}

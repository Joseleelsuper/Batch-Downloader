package es.ubu.batchdownloader.downloadworker.ports;

import java.nio.file.Path;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Define el contrato de {@code ArchiveBuilder}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface ArchiveBuilder {
    /** Permite añadir entradas mientras el ZIP permanece abierto. */
    interface ArchiveWriter {
        /** Añade un archivo local. */
        void add(String path, Path source) throws IOException;
        /** Añade contenido pequeño ya materializado. */
        void add(String path, byte[] content) throws IOException;
    }

    /** Produce las entradas de un archivo abierto. */
    @FunctionalInterface
    interface ArchiveContents {
        /** Escribe todas las entradas requeridas. */
        void write(ArchiveWriter writer) throws IOException;
    }
    /**
     * Construye el resultado solicitado mediante {@code build}.
     *
     * @param target Valor de {@code target} utilizado por la operación.
     * @param artifacts Valor de {@code artifacts} utilizado por la operación.
     * @param supplementalEntries Valor de {@code supplementalEntries} utilizado por la operación.
     * @param manifest Valor de {@code manifest} utilizado por la operación.
     */
    void build(OutputStream target, int compressionLevel, ArchiveContents contents);
}

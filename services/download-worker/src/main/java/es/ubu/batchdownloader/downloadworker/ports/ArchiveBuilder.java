package es.ubu.batchdownloader.downloadworker.ports;

import java.nio.file.Path;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Produce un archivo a partir de entradas suministradas mientras permanece abierto, para encadenar
 * descarga y almacenamiento por streaming sin materializar un ZIP completo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see ArchiveBuilder.ArchiveContents
 * @see ArchiveBuilder.ArchiveWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Construcción de archivos
 */
public interface ArchiveBuilder {
    /**
     * Añade archivos locales o contenido pequeño durante la construcción; sus instancias no deben
     * conservarse fuera del callback del archivo.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Construcción de archivos
     */
    interface ArchiveWriter {
        /**
         * Copia un archivo local a una entrada del archivo abierto sin exigir cargarlo entero en
         * memoria.
         *
         * @param path Nombre relativo de la entrada dentro del archivo, con / como separador.
         * @param source Archivo local que se lee por streaming, sin cargarlo completo en memoria.
         * @throws java.io.IOException si no se puede leer el archivo o escribir la entrada.
         */
        void add(String path, Path source) throws IOException;
        /**
         * Escribe el contenido ya materializado en una entrada del archivo abierto.
         *
         * @param path Nombre relativo de la entrada dentro del archivo, con / como separador.
         * @param content Contenido pequeño ya materializado que se escribe como una entrada.
         * @throws java.io.IOException si falla la escritura de la entrada.
         */
        void add(String path, byte[] content) throws IOException;
        /**
         * Permite solicitar una entrada ejecutable para scripts del runtime. La implementación por
         * defecto escribe una entrada normal; los adaptadores que soportan permisos deben
         * especializarla.
         *
         * @param path Nombre relativo de la entrada dentro del archivo, con / como separador.
         * @param content Contenido pequeño ya materializado que se escribe como una entrada.
         * @throws java.io.IOException si falla la escritura de la entrada.
         */
        default void addExecutable(String path, byte[] content) throws IOException {
            add(path, content);
        }
    }

    /**
     * Suministra las entradas de forma secuencial mientras el constructor mantiene abierto el
     * archivo.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Construcción de archivos
     */
    @FunctionalInterface
    interface ArchiveContents {
        /**
         * Añade al escritor todas las entradas necesarias antes de devolver el control al
         * constructor que cierra el archivo.
         *
         * @param writer Escritor válido únicamente durante la producción del archivo abierto.
         * @throws java.io.IOException si falla la lectura o escritura durante la producción de
         *     alguna entrada.
         */
        void write(ArchiveWriter writer) throws IOException;
    }
    /**
     * Abre el archivo sobre el destino, entrega su escritor al productor y finaliza las entradas
     * con el nivel de compresión solicitado.
     *
     * @param target Flujo de salida del archivo; la implementación ZIP lo cierra al terminar o
     *     fallar.
     * @param compressionLevel Nivel de compresión solicitado; ZIP lo acota entre cero y nueve.
     * @param contents Productor que añade entradas mientras el archivo permanece abierto.
     */
    void build(OutputStream target, int compressionLevel, ArchiveContents contents);
}

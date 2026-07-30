package es.ubu.batchdownloader.downloadworker.ports;

import java.nio.file.Path;

/**
 * Define el contrato de {@code ArtifactStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface ArtifactStore {
    /**
     * Ejecuta la operación {@code put}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param source Fuente de descarga sobre la que se actúa.
     * @param contentType Valor de {@code contentType} utilizado por la operación.
     */
    void put(String objectKey, Path source, String contentType);

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     */
    default void delete(String objectKey) {
        // Una operación vacía mantiene los dobles ligeros centrados en los objetos expuestos.
    }
}

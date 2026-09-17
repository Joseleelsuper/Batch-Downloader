package es.ubu.batchdownloader.downloadworker.ports;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Guarda instaladores y archivos producidos por el worker y permite conocer integridad y ocupación.
 * Los adaptadores de producción pueden especializar escritura streaming, borrado y uso de espacio.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see ArtifactStore.StoredArtifact
 * @see ArtifactStore.StreamWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface ArtifactStore {
    /**
     * Devuelve tamaño e integridad del contenido que terminó de escribirse en almacenamiento.
     *
     * @param sizeBytes Longitud total persistida del objeto, en bytes.
     * @param sha256 SHA-256 hexadecimal calculado sobre todos los bytes escritos.
     * @since 0.1.0
     * @version 0.1.0
     * @category Puertos del worker
     */
    record StoredArtifact(long sizeBytes, String sha256) {}

    /**
     * Produce el contenido de un objeto durante una única escritura sobre el flujo suministrado por
     * el almacén.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Puertos del worker
     */
    @FunctionalInterface
    interface StreamWriter {
        /**
         * Escribe el contenido completo del objeto antes de devolver el control al almacén que
         * finaliza su persistencia.
         *
         * @param output Flujo de destino sobre el que se escriben o cuentan los bytes.
         * @throws java.io.IOException si falla la generación o la escritura del contenido.
         */
        void write(OutputStream output) throws IOException;
    }
    /**
     * Persiste el archivo local bajo su clave y tipo MIME; el llamador conserva la responsabilidad
     * sobre su temporal.
     *
     * @param objectKey Clave del objeto dentro del almacén del worker, sin incluir credenciales ni
     *     URL firmada.
     * @param source Archivo local completo que debe persistirse antes de confirmar la operación.
     * @param contentType Tipo MIME que se guarda como metadato del objeto.
     */
    void put(String objectKey, Path source, String contentType);

    /**
     * Ofrece un respaldo que materializa un temporal, cuenta y calcula SHA-256 mientras escribe y
     * después delega su almacenamiento. Intenta borrar el temporal incluso si la producción o
     * subida falla.
     *
     * @param objectKey Clave del objeto dentro del almacén del worker, sin incluir credenciales ni
     *     URL firmada.
     * @param contentType Tipo MIME que se guarda como metadato del objeto.
     * @param partSize Tamaño de parte solicitado en bytes para adaptadores multipart; el respaldo
     *     local no lo utiliza.
     * @param writer Productor del contenido que recibe un flujo válido solo durante la escritura.
     * @return tamaño y SHA-256 del contenido escrito.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla la
     *     E/S temporal o no se dispone de SHA-256.
     */
    default StoredArtifact putStreaming(
            String objectKey,
            String contentType,
            long partSize,
            StreamWriter writer) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("artifact-store-", ".tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            CountingOutputStream counting;
            try (OutputStream file = Files.newOutputStream(temporary)) {
                counting = new CountingOutputStream(new DigestOutputStream(file, digest));
                writer.write(counting);
                counting.flush();
            }
            put(objectKey, temporary, contentType);
            return new StoredArtifact(counting.count(), HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new InfrastructureException("artifact_stream_failed", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // El directorio temporal del sistema realiza la limpieza de respaldo.
                }
            }
        }
    }

    /**
     * Guarda contenido pequeño mediante la misma escritura que calcula tamaño y huella del objeto.
     *
     * @param objectKey Clave del objeto dentro del almacén del worker, sin incluir credenciales ni
     *     URL firmada.
     * @param content Bytes ya materializados del objeto pequeño que se guarda.
     * @param contentType Tipo MIME que se guarda como metadato del objeto.
     * @param partSize Tamaño de parte solicitado en bytes para adaptadores multipart; el respaldo
     *     local no lo utiliza.
     * @return integridad y longitud calculadas del contenido persistido.
     */
    default StoredArtifact putBytes(String objectKey, byte[] content, String contentType, long partSize) {
        return putStreaming(objectKey, contentType, partSize, output -> output.write(content));
    }

    /**
     * Permite retirar un objeto del trabajo; el contrato por defecto no realiza ninguna operación y
     * los adaptadores persistentes deben especializarlo.
     *
     * @param objectKey Clave del objeto dentro del almacén del worker, sin incluir credenciales ni
     *     URL firmada.
     */
    default void delete(String objectKey) {
        // Una operación vacía mantiene los dobles ligeros centrados en los objetos expuestos.
    }

    /**
     * Consulta ocupación para aplicar reservas de almacenamiento; el contrato por defecto no
     * informa ocupación.
     *
     * @return bytes ocupados; cero en la implementación por defecto.
     */
    default long usageBytes() {
        return 0L;
    }

    /**
     * Cuenta los bytes que el flujo subyacente aceptó para calcular la longitud del objeto mientras
     * se genera.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Puertos del worker
     */
    final class CountingOutputStream extends FilterOutputStream {
        /**
         * Longitud escrita en bytes.
         */
        private long count;

        /**
         * Conecta el contador con el flujo de salida al que delega cada escritura.
         *
         * @param output Flujo de destino sobre el que se escriben o cuentan los bytes.
         */
        public CountingOutputStream(OutputStream output) {
            super(output);
        }

        /**
         * Escribe un byte y lo suma al contador únicamente cuando la escritura ha terminado.
         *
         * @param value Byte que se escribe usando los ocho bits inferiores del entero.
         * @throws java.io.IOException si el destino rechaza la escritura.
         */
        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        /**
         * Escribe directamente el tramo y suma su longitud una sola vez al contador.
         *
         * @param bytes Búfer que contiene el tramo de datos que se copia.
         * @param offset Índice inicial del tramo dentro del búfer, en bytes.
         * @param length Cantidad de bytes del tramo que se copia.
         * @throws java.io.IOException si falla la escritura del tramo.
         */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }

        /**
         * Devuelve la cantidad acumulada de bytes escritos por este flujo.
         *
         * @return longitud escrita en bytes.
         */
        public long count() {
            return count;
        }
    }
}

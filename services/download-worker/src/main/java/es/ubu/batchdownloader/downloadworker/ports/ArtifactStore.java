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
 * Define el contrato de {@code ArtifactStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface ArtifactStore {
    /** Resultado verificable de una escritura de objeto. */
    record StoredArtifact(long sizeBytes, String sha256) {}

    /** Escribe de forma incremental el contenido de un objeto. */
    @FunctionalInterface
    interface StreamWriter {
        /**
         * Escribe todo el objeto sin cerrar el flujo recibido.
         *
         * @param output Destino del objeto.
         * @throws IOException Si falla la escritura.
         */
        void write(OutputStream output) throws IOException;
    }
    /**
     * Ejecuta la operación {@code put}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param source Fuente de descarga sobre la que se actúa.
     * @param contentType Valor de {@code contentType} utilizado por la operación.
     */
    void put(String objectKey, Path source, String contentType);

    /**
     * Escribe un objeto mediante un flujo y devuelve su tamaño y huella sin releerlo.
     * La implementación predeterminada facilita dobles de prueba; MinIO la sustituye
     * por multipart real.
     *
     * @param objectKey Clave del objeto.
     * @param contentType Tipo MIME.
     * @param partSize Tamaño de parte multipart.
     * @param writer Productor del contenido.
     * @return Metadatos calculados durante la escritura.
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

    /** Escribe un objeto pequeño en memoria mediante el mismo camino verificable. */
    default StoredArtifact putBytes(String objectKey, byte[] content, String contentType, long partSize) {
        return putStreaming(objectKey, contentType, partSize, output -> output.write(content));
    }

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     */
    default void delete(String objectKey) {
        // Una operación vacía mantiene los dobles ligeros centrados en los objetos expuestos.
    }

    /** @return Bytes actualmente almacenados bajo el prefijo de trabajos. */
    default long usageBytes() {
        return 0L;
    }

    /** Cuenta los bytes que atraviesan un flujo. */
    final class CountingOutputStream extends FilterOutputStream {
        /** Bytes escritos. */
        private long count;

        /** Inicializa el contador. */
        public CountingOutputStream(OutputStream output) {
            super(output);
        }

        /** {@inheritDoc} */
        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        /** {@inheritDoc} */
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }

        /** @return Bytes escritos. */
        public long count() {
            return count;
        }
    }
}

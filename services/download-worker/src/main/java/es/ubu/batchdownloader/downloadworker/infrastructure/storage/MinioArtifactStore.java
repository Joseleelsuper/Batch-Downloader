package es.ubu.batchdownloader.downloadworker.infrastructure.storage;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementa el componente {@code MinioArtifactStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@SuppressWarnings("java:S2221") // MinIO's public operations declare a heterogeneous checked Exception set.
public class MinioArtifactStore implements ArtifactStore {
    /**
     * Estado {@code client} mantenido por {@code MinioArtifactStore}.
     */
    private final MinioClient client;
    /**
     * Estado {@code properties} mantenido por {@code MinioArtifactStore}.
     */
    private final StorageProperties properties;
    /**
     * Estado {@code bucketReady} mantenido por {@code MinioArtifactStore}.
     */
    private volatile boolean bucketReady;

    /**
     * Inicializa una instancia de {@code MinioArtifactStore}.
     *
     * @param client Valor de {@code client} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public MinioArtifactStore(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Implementa {@code put} para {@code MinioArtifactStore}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param source Fuente de descarga sobre la que se actúa.
     * @param contentType Valor de {@code contentType} utilizado por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Override
    public void put(String objectKey, Path source, String contentType) {
        ensureBucket();
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .filename(source.toString())
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new InfrastructureException("minio_upload_failed", exception);
        }
    }

    /**
     * Sube el objeto a medida que se produce y calcula sus metadatos sin releerlo.
     *
     * @param objectKey Clave del objeto.
     * @param contentType Tipo MIME.
     * @param partSize Tamaño de cada parte multipart.
     * @param writer Productor del contenido.
     * @return Tamaño y SHA-256 calculados en línea.
     */
    @Override
    public StoredArtifact putStreaming(
            String objectKey,
            String contentType,
            long partSize,
            StreamWriter writer) {
        ensureBucket();
        AtomicReference<Throwable> uploadFailure = new AtomicReference<>();
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int pipeBuffer = (int) Math.clamp(partSize, 64L * 1024, 16L * 1024 * 1024);
            try (PipedInputStream pipeInput = new PipedInputStream(pipeBuffer);
                    PipedOutputStream pipe = new PipedOutputStream(pipeInput);
                    InputStream input = new ProducerAwareInputStream(pipeInput, producerFailure)) {
                Thread uploader = Thread.ofVirtual().name("minio-multipart-upload").start(() -> {
                    try {
                        client.putObject(PutObjectArgs.builder()
                                .bucket(properties.bucket())
                                .object(objectKey)
                                .contentType(contentType)
                                .stream(input, -1, partSize)
                                .build());
                    } catch (Throwable exception) {
                        uploadFailure.set(exception);
                        try {
                            input.close();
                        } catch (IOException ignored) {
                            // La excepción original conserva la causa útil.
                        }
                    }
                });
                CountingOutputStream counting = new CountingOutputStream(
                        new DigestOutputStream(pipe, digest));
                Throwable writeFailure = null;
                try {
                    writer.write(counting);
                    counting.close();
                } catch (Throwable exception) {
                    writeFailure = exception;
                    if (uploadFailure.get() == null) {
                        producerFailure.compareAndSet(null, exception);
                    }
                    try {
                        counting.close();
                    } catch (IOException ignored) {
                        // La excepción de escritura es la causa principal.
                    }
                }
                try {
                    uploader.join();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    uploader.interrupt();
                    throw new InfrastructureException("minio_upload_interrupted", exception);
                }
                if (producerFailure.get() != null) {
                    rethrowWriterFailure(producerFailure.get());
                }
                if (uploadFailure.get() != null) {
                    throw new InfrastructureException("minio_upload_failed", uploadFailure.get());
                }
                if (writeFailure != null) {
                    rethrowWriterFailure(writeFailure);
                }
                return new StoredArtifact(
                        counting.count(), HexFormat.of().formatHex(digest.digest()));
            }
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(objectKey);
            throw new InfrastructureException("minio_upload_failed", exception);
        } catch (RuntimeException exception) {
            deleteQuietly(objectKey);
            throw exception;
        }
    }

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Override
    public void delete(String objectKey) {
        ensureBucket();
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new InfrastructureException("minio_delete_failed", exception);
        }
    }

    /** Cuenta por separado los objetos persistidos; las reservas en vuelo viven en ArtifactCapacity. */
    @Override
    public long usageBytes() {
        ensureBucket();
        long total = 0;
        try {
            Iterable<Result<Item>> objects = client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.bucket())
                    .prefix("jobs/")
                    .recursive(true)
                    .build());
            for (Result<Item> result : objects) {
                total = Math.addExact(total, result.get().size());
            }
            return total;
        } catch (Exception exception) {
            throw new InfrastructureException("minio_usage_failed", exception);
        }
    }

    /**
     * Ejecuta la operación {@code ensureBucket}.
     *
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = client.bucketExists(
                        BucketExistsArgs.builder().bucket(properties.bucket()).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                }
                bucketReady = true;
            } catch (Exception exception) {
                throw new InfrastructureException("minio_bucket_initialization_failed", exception);
            }
        }
    }

    /** Propaga las excepciones de negocio producidas por el generador del objeto. */
    private void rethrowWriterFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new InfrastructureException("minio_stream_writer_failed", failure);
    }

    /** Elimina cualquier objeto parcial visible después de un error. */
    private void deleteQuietly(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // El SDK aborta el multipart; el ciclo de vida actúa como respaldo adicional.
        }
    }

    /**
     * Convierte el cierre anticipado del productor en un error de lectura. De este modo el SDK
     * aborta la subida multipart en vez de interpretar el cierre como un objeto completo.
     */
    private static final class ProducerAwareInputStream extends FilterInputStream {
        /** Fallo original del productor, si lo hubo. */
        private final AtomicReference<Throwable> producerFailure;

        /** Inicializa la vista de lectura sobre la tubería. */
        private ProducerAwareInputStream(
                InputStream input,
                AtomicReference<Throwable> producerFailure) {
            super(input);
            this.producerFailure = producerFailure;
        }

        /** {@inheritDoc} */
        @Override
        public int read() throws IOException {
            int value = super.read();
            failOnPrematureEnd(value);
            return value;
        }

        /** {@inheritDoc} */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            failOnPrematureEnd(read);
            return read;
        }

        /** Lanza la causa original cuando el productor no terminó correctamente. */
        private void failOnPrematureEnd(int read) throws IOException {
            Throwable failure = producerFailure.get();
            if (read < 0 && failure != null) {
                throw new IOException("Multipart producer failed", failure);
            }
        }
    }
}

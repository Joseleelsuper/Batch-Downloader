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
 * Persiste artefactos en MinIO y transmite ZIP mediante una tubería acotada entre productor y
 * subida multipart, calculando longitud y huella sin crear otro ZIP temporal.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.ArtifactStore
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Adaptadores y persistencia del worker
 */
@SuppressWarnings("java:S2221") // MinIO's public operations declare a heterogeneous checked Exception set.
public class MinioArtifactStore implements ArtifactStore {
    /**
     * Estado {@code client} mantenido por {@code MinioArtifactStore}.
     */
    private final MinioClient client;
    private final MinioMultipartClient multipart;
    /**
     * Estado {@code properties} mantenido por {@code MinioArtifactStore}.
     */
    private final StorageProperties properties;
    /**
     * Estado {@code bucketReady} mantenido por {@code MinioArtifactStore}.
     */
    private volatile boolean bucketReady;

    /**
     * Conecta el cliente y bucket configurados, aplazando su comprobación hasta la primera
     * operación.
     *
     * @param client Cliente del servicio remoto, configurado antes de componer el adaptador.
     * @param properties Configuración específica del adaptador: destino, credencial y límites de
     *     acceso.
     */
    public MinioArtifactStore(MinioClient client, StorageProperties properties) {
        this(client, properties, new MinioMultipartClient(io.minio.MinioAsyncClient.builder()
                .endpoint(properties.endpoint()).credentials(properties.accessKey(), properties.secretKey()).build()));
    }

    MinioArtifactStore(MinioClient client, StorageProperties properties, MinioMultipartClient multipart) {
        this.client = client;
        this.properties = properties;
        this.multipart = multipart;
    }

    /**
     * Comprueba el bucket y sube un archivo local con la clave y tipo MIME indicados.
     *
     * @param objectKey Clave del objeto que se almacena o elimina dentro del bucket configurado.
     * @param source Ruta local del archivo completo que debe subirse.
     * @param contentType Tipo MIME persistido como metadato del objeto.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla la
     *     preparación del bucket o la subida del archivo.
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
     * Genera y calcula la integridad del contenido mientras un hilo virtual lo sube por multipart.
     * Espera ambos lados y evita aceptar un EOF causado por fallo del productor; intenta retirar el
     * objeto al fallar.
     *
     * @param objectKey Clave del objeto que se almacena o elimina dentro del bucket configurado.
     * @param contentType Tipo MIME persistido como metadato del objeto.
     * @param partSize Tamaño multipart solicitado en bytes; el búfer de enlace se acota entre 64
     *     KiB y 16 MiB.
     * @param writer Productor que escribe al flujo contado y calculado mientras el hilo de subida
     *     consume sus bytes.
     * @return tamaño y SHA-256 tras completar producción y subida.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla el
     *     bucket, multipart, la espera o la producción con una causa no RuntimeException.
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
                var uploadStopped = new java.util.concurrent.CompletableFuture<Void>();
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
                    } finally {
                        uploadStopped.complete(null);
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
                    // Interrumpir el wrapper síncrono del SDK dejaría vivo su futuro HTTP.
                    try { input.close(); } catch (IOException ignored) { /* Se espera al uploader igualmente. */ }
                    uploadStopped.join(); // Espera no interrumpible: ya no quedan escrituras ni cierres pendientes.
                    Thread.currentThread().interrupt();
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
     * Comprueba el bucket y solicita eliminar el objeto indicado.
     *
     * @param objectKey Clave del objeto que se almacena o elimina dentro del bucket configurado.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no puede
     *     preparar el bucket o retirar el objeto.
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

    /**
     * Recorre los objetos bajo jobs/ y suma sus tamaños con detección de desbordamiento para
     * aplicar la cuota del worker.
     *
     * @return bytes persistidos de trabajos en el bucket.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla el
     *     listado, la lectura de un objeto o la suma de tamaños.
     */
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

    @Override
    public java.util.Map<java.util.UUID, Long> jobUsage(java.util.Collection<java.util.UUID> knownJobs) {
        ensureBucket();
        java.util.Map<java.util.UUID, Long> usage = new java.util.HashMap<>();
        try {
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.bucket()).prefix("jobs/").recursive(true).build())) {
                Item item = result.get();
                addUsage(usage, item.objectName(), item.size());
            }
            var jobIds = new java.util.HashSet<>(knownJobs);
            jobIds.addAll(usage.keySet());
            for (java.util.UUID jobId : jobIds) {
                for (var upload : incompleteJob(jobId)) {
                    long bytes = 0;
                    int marker = 0;
                    io.minio.messages.ListPartsResult parts;
                    do {
                        parts = multipart.listPartsAsync(properties.bucket(), null, upload.objectName(),
                                1000, marker, upload.uploadId(), null, null).get().result();
                        for (io.minio.messages.Part part : parts.partList()) bytes = Math.addExact(bytes, part.partSize());
                        marker = parts.nextPartNumberMarker();
                    } while (parts.isTruncated());
                    addUsage(usage, upload.objectName(), bytes);
                }
            }
            return usage;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("minio_inventory_failed", exception);
        } catch (Exception exception) {
            throw new InfrastructureException("minio_inventory_failed", exception);
        }
    }

    @Override
    public void deleteJob(java.util.UUID jobId) {
        ensureBucket();
        String prefix = "jobs/" + jobId + "/";
        try {
            for (var upload : incompleteJob(jobId)) {
                multipart.abortMultipartUploadAsync(properties.bucket(), null, upload.objectName(),
                        upload.uploadId(), null, null).get();
            }
            for (Result<Item> result : client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.bucket()).prefix(prefix).recursive(true).build())) {
                delete(result.get().objectName());
            }
            if (!incompleteJob(jobId).isEmpty() || client.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.bucket()).prefix(prefix).recursive(true).build()).iterator().hasNext()) {
                throw new IOException("Job files still present after cleanup");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("minio_cleanup_failed", exception);
        } catch (Exception exception) {
            throw new InfrastructureException("minio_cleanup_failed", exception);
        }
    }

    private java.util.List<MinioMultipartClient.PendingUpload> incompleteJob(java.util.UUID jobId) throws Exception {
        // MinIO solo lista multipart para una clave exacta; jobs/ no devuelve sus descendientes.
        // El inbox conserva cada jobId antes de escribir estas dos únicas claves del worker.
        var uploads = new java.util.ArrayList<MinioMultipartClient.PendingUpload>();
        uploads.addAll(multipart.incomplete(properties.bucket(), "jobs/" + jobId + "/bundle.zip"));
        uploads.addAll(multipart.incomplete(properties.bucket(), "jobs/" + jobId + "/manifest.json"));
        return uploads;
    }

    private void addUsage(java.util.Map<java.util.UUID, Long> usage, String key, long bytes) {
        String[] parts = key.split("/", 3);
        if (parts.length == 3 && parts[0].equals("jobs")) {
            usage.merge(java.util.UUID.fromString(parts[1]), bytes, Math::addExact);
        }
    }

    /**
     * Comprueba o crea el bucket una sola vez por instancia bajo un cerrojo y conserva la
     * inicialización satisfactoria.
     *
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no puede
     *     comprobar o crear el bucket.
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

    /**
     * Propaga sin cambiar una RuntimeException del productor y envuelve otras causas como
     * minio_stream_writer_failed.
     *
     * @param failure Fallo original del productor del contenido.
     */
    private void rethrowWriterFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        throw new InfrastructureException("minio_stream_writer_failed", failure);
    }

    /**
     * Intenta retirar el objeto incompleto después de un fallo de streaming sin sustituir la
     * excepción que provocó la compensación.
     *
     * @param objectKey Clave del objeto que se almacena o elimina dentro del bucket configurado.
     */
    private void deleteQuietly(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .build());
        } catch (Exception ignored) {
            // La limpieza confirmada del trabajo reintenta objetos y partes antes de liberar reserva.
        }
    }

    /**
     * Impide que el consumidor multipart interprete como archivo completo un fin de flujo producido
     * por un fallo del escritor.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Adaptadores y persistencia del worker
     */
    private static final class ProducerAwareInputStream extends FilterInputStream {
        /** Fallo original del productor, si lo hubo. */
        private final AtomicReference<Throwable> producerFailure;

        /**
         * Conecta la tubería y la referencia al fallo de producción compartida con el escritor.
         *
         * @param input Flujo de lectura conectado al productor mediante una tubería acotada.
         * @param producerFailure Referencia compartida al fallo de producción que impide aceptar
         *     EOF como éxito.
         */
        private ProducerAwareInputStream(
                InputStream input,
                AtomicReference<Throwable> producerFailure) {
            super(input);
            this.producerFailure = producerFailure;
        }

        /**
         * Lee un byte y comprueba que un fin de flujo no oculte un fallo del productor.
         *
         * @return byte leído o -1 al terminar correctamente.
         * @throws java.io.IOException si falla la lectura o terminó el productor con error.
         */
        @Override
        public int read() throws IOException {
            int value = super.read();
            failOnPrematureEnd(value);
            return value;
        }

        /**
         * Lee un tramo y comprueba la causa de un posible fin de flujo antes de devolverlo a MinIO.
         *
         * @param bytes Búfer de destino de la lectura del multipart.
         * @param offset Índice inicial del tramo del búfer, en bytes.
         * @param length Máximo de bytes que se solicita leer.
         * @return cantidad leída o -1 al terminar correctamente.
         * @throws java.io.IOException si falla la lectura o el productor terminó con error.
         */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            failOnPrematureEnd(read);
            return read;
        }

        /**
         * Consulta la causa compartida cuando la lectura señala EOF y la propaga como fallo de E/S.
         *
         * @param read Resultado de la lectura; un valor negativo indica fin de flujo.
         * @throws java.io.IOException si el escritor había fallado antes de cerrar la tubería.
         */
        private void failOnPrematureEnd(int read) throws IOException {
            Throwable failure = producerFailure.get();
            if (read < 0 && failure != null) {
                throw new IOException("Multipart producer failed", failure);
            }
        }
    }
}

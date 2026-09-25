package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gestiona directorios temporales y compensación de objetos de un trabajo, registrando los fallos
 * de limpieza sin sustituir el resultado principal del procesamiento.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @see es.ubu.batchdownloader.downloadworker.ports.ArtifactStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Resultados y empaquetado
 */
public final class DownloadJobFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobFiles.class);

    private final ArtifactStore artifactStore;
    private final DownloadWorkerMetrics metrics;
    private final DownloadProperties properties;

    /**
     * Conecta el almacén, las métricas de temporales y su directorio base.
     *
     * @param artifactStore Almacenamiento de ZIP y manifiesto con integridad de los bytes escritos.
     * @param metrics Contadores y temporizadores de actividad, temporales y empaquetado.
     * @param properties Límites de cantidad, concurrencia, bytes y empaquetado del worker.
     */
    public DownloadJobFiles(
            ArtifactStore artifactStore,
            DownloadWorkerMetrics metrics,
            DownloadProperties properties) {
        this.artifactStore = artifactStore;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * Crea un directorio único bajo la base configurada con el UUID del trabajo como prefijo y sin
     * reutilizar una ejecución anterior.
     *
     * @param jobId UUID del trabajo cuya cancelación se comprueba durante la espera.
     * @return nuevo directorio temporal del trabajo.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no se
     *     puede crear la base o el directorio de ejecución.
     */
    Path createDirectory(UUID jobId) {
        try {
            Path base = Path.of(properties.tempDirectory());
            Files.createDirectories(base);
            return Files.createTempDirectory(base, jobId + "-");
        } catch (IOException exception) {
            throw new InfrastructureException("temp_directory_creation_failed", exception);
        }
    }

    /**
     * Intenta borrar un archivo si existe; registra los fallos de E/S sin interrumpir la
     * compensación del trabajo.
     *
     * @param path Ruta local del archivo temporal que se intenta eliminar.
     */
    void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.debug("Could not delete temporary download path {}", path, exception);
        }
    }

    /**
     * Intenta retirar un objeto incompleto; registra los fallos del almacén y permite continuar la
     * limpieza restante.
     *
     * @param objectKey Clave del objeto incompleto que se intenta retirar del almacén.
     */
    void deleteStored(String objectKey) {
        try {
            artifactStore.delete(objectKey);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not delete incomplete object {}", objectKey, exception);
        }
    }

    /**
     * Descuenta de la métrica el tamaño legible del directorio e intenta eliminar recursivamente su
     * contenido.
     *
     * @param root Directorio temporal exclusivo del trabajo; null o inexistente no requiere
     *     limpieza.
     */
    void removeDirectory(Path root) {
        metrics.temporaryRemoved(size(root));
        deleteRecursively(root);
    }

    /** El borrado confirmado es requisito para liberar capacidad; los fallos se reintentan. */
    public void clean(UUID jobId, boolean includeArtifacts) {
        Path base = Path.of(properties.tempDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
            try (var directories = Files.list(base)) {
                for (Path directory : directories.filter(path -> path.getFileName().toString()
                        .startsWith(jobId + "-")).toList()) {
                    Path checked = directory.toAbsolutePath().normalize();
                    if (!checked.getParent().equals(base)) throw new IOException("Invalid temporary directory");
                    long bytes = size(checked);
                    try (var paths = Files.walk(checked)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                    metrics.temporaryRemoved(bytes);
                }
            }
            if (includeArtifacts) artifactStore.deleteJob(jobId);
        } catch (IOException exception) {
            throw new InfrastructureException("download_cleanup_failed", exception);
        }
    }

    public java.util.Map<UUID, Long> usage(java.util.Collection<UUID> knownJobs) {
        java.util.Map<UUID, Long> usage = new java.util.HashMap<>(artifactStore.jobUsage(knownJobs));
        Path base = Path.of(properties.tempDirectory());
        try {
            Files.createDirectories(base);
            try (var paths = Files.list(base)) {
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    if (name.length() > 36 && name.charAt(36) == '-') {
                        try {
                            UUID id = UUID.fromString(name.substring(0, 36));
                            usage.merge(id, strictSize(path), Math::addExact);
                        } catch (IllegalArgumentException ignored) {
                            // No es un directorio creado por el worker.
                        }
                    }
                }
            }
            return usage;
        } catch (IOException exception) {
            throw new InfrastructureException("download_inventory_failed", exception);
        }
    }

    private long strictSize(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            long bytes = 0;
            for (Path file : paths.filter(Files::isRegularFile).toList()) bytes = Math.addExact(bytes, Files.size(file));
            return bytes;
        }
    }

    /**
     * Suma tamaños de archivos regulares para compensar la métrica de temporales; ignora archivos o
     * recorridos que no pueden leerse.
     *
     * @param root Directorio temporal exclusivo del trabajo; null o inexistente no requiere
     *     limpieza.
     * @return bytes que se pudieron medir; cero para ausencia o fallo del recorrido.
     */
    private long size(Path root) {
        if (root == null || !Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0;
                }
            }).sum();
        } catch (IOException exception) {
            return 0;
        }
    }

    /**
     * Recorre el directorio y elimina primero sus descendientes; registra fallos de recorrido o
     * borrado y continúa donde puede.
     *
     * @param root Directorio temporal exclusivo del trabajo; null o inexistente no requiere
     *     limpieza.
     */
    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.debug("Could not delete temporary download path {}", path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.debug("Could not traverse temporary download directory {}", root, exception);
        }
    }
}

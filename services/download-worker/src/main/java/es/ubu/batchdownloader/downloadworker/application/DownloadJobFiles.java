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

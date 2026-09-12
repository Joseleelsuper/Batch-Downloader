package es.ubu.batchdownloader.downloads.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agrupa cuotas de admisión y duraciones del ZIP procedentes de las propiedades app.download.
 *
 * @param maxApps Máximo de aplicaciones admitidas, incluidas las dependencias añadidas.
 * @param zipRetention Duración máxima durante la que se conserva disponible el ZIP.
 * @param presignedUrlTtl Vigencia de cada enlace firmado, independiente de la retención del ZIP.
 * @param anonymousMaxActiveJobs Máximo de trabajos no terminales por navegador anónimo.
 * @param anonymousMaxCreatesPerHour Máximo de creaciones por navegador en una ventana de una hora.
 * @param anonymousMaxCreatesPerIpHour Máximo de creaciones por hash de dirección IP durante una
 *     hora.
 *
 * @param authenticatedMaxActiveJobs Máximo de trabajos no terminales por cuenta autenticada.
 * @param globalMaxPendingJobs Máximo global de trabajos no terminales admitidos en la cola.
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobService
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobExpiration
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@ConfigurationProperties("app.download")
public record DownloadLimits(
        int maxApps,
        Duration zipRetention,
        Duration presignedUrlTtl,
        int anonymousMaxActiveJobs,
        int anonymousMaxCreatesPerHour,
        int anonymousMaxCreatesPerIpHour,
        int authenticatedMaxActiveJobs,
        int globalMaxPendingJobs) {}

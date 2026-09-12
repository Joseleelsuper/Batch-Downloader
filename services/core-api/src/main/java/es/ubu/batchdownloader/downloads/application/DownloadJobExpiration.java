package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retira la disponibilidad de ZIP vencidos antes de limpiar sus objetos fuera de la transacción de
 * base de datos.
 *
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Service
public class DownloadJobExpiration {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobExpiration.class);
    /**
     * Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     */
    private final DownloadJobStore jobs;
    /**
     * Puerto de eliminación de objetos y temporales asociados a un trabajo.
     */
    private final DownloadArtifactCleaner artifacts;
    /**
     * Difusor de cambios de estado hacia consumidores suscritos.
     */
    private final DownloadJobNotifier notifier;
    /**
     * Reloj que determina cuotas, cambios de estado y vencimientos.
     */
    private final Clock clock;
    /**
     * Ejecutor de transacciones cortas para guardar la expiración antes de limpiar archivos.
     */
    private final TransactionTemplate transactions;

    /**
     * Conecta el reloj de expiración, la persistencia transaccional y la limpieza de objetos
     * publicados.
     *
     * @param jobs Persistencia de trabajos, elementos, contexto Linux y reservas de admisión.
     * @param artifacts Puerto de eliminación de objetos y temporales asociados a un trabajo.
     * @param notifier Difusor de cambios de estado hacia consumidores suscritos.
     * @param clock Reloj que determina cuotas, cambios de estado y vencimientos.
     * @param transactions Ejecutor de transacciones cortas para guardar la expiración antes de
     *     limpiar archivos.
     */
    public DownloadJobExpiration(DownloadJobStore jobs, DownloadArtifactCleaner artifacts, DownloadJobNotifier notifier, Clock clock, TransactionTemplate transactions) {
        this.jobs = jobs;
        this.artifacts = artifacts;
        this.notifier = notifier;
        this.clock = clock;
        this.transactions = transactions;
    }

    /**
     * Cada diez minutos marca vencidos los trabajos descargables, confirma sus cambios y después
     * elimina artefactos y difunde el estado.
     */
    @Scheduled(fixedDelayString = "PT10M")
    public void expireReadyJobs() {
        Instant now = clock.instant();
        List<DownloadJobView> expiredViews = transactions.execute(status ->
                jobs.findDownloadableExpiredBefore(now).stream()
                        .filter(job -> job.expire(now))
                        .map(jobs::save)
                        .map(DownloadJobView::from)
                        .toList());
        if (expiredViews == null) {
            return;
        }
        expiredViews.forEach(view -> {
            deleteExpiredArtifacts(view.id());
            notifier.changed(view);
        });
    }

    /**
     * Intenta tres veces eliminar los objetos del trabajo; si falla registra el incidente para la
     * limpieza posterior del almacén.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     */
    private void deleteExpiredArtifacts(UUID jobId) {
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                artifacts.deleteJobArtifacts(jobId);
                return;
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        LOGGER.warn("Could not remove expired download artifacts for job {} after 3 attempts", jobId, failure);
    }

}

package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Difunde instantáneas confirmadas del trabajo a los consumidores suscritos.
 *
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Service
public class DownloadJobNotifications {
    /**
     * Difusor de cambios de estado hacia consumidores suscritos.
     */
    private final DownloadJobNotifier notifier;
    private final DownloadStorageCoordinator storage;
    /**
     * Conecta el difusor de cambios de estado hacia consumidores suscritos.
     *
     * @param notifier Difusor de cambios de estado hacia consumidores suscritos.
     */
    public DownloadJobNotifications(DownloadJobNotifier notifier, DownloadStorageCoordinator storage) {
        this.notifier = notifier;
        this.storage = storage;
    }

    /**
     * Convierte el agregado guardado en una instantánea y la programa para después del commit.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    public void notifyAfterSave(DownloadJob job) {
        notifyAfterCommit(DownloadJobView.from(job));
    }

    /**
     * Difunde al confirmar la transacción; si no existe sincronización transaccional activa,
     * difunde inmediatamente.
     *
     * @param view Instantánea que debe difundirse únicamente después de confirmar sus cambios.
     */
    public void notifyAfterCommit(DownloadJobView view) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifier.changed(storage.decorate(view));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * Envía la instantánea solo después de que la transacción termine correctamente.
             */
            @Override
            public void afterCommit() {
                notifier.changed(storage.decorate(view));
            }
        });
    }

}

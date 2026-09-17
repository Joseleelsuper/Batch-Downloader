package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Difunde instantáneas confirmadas y solicita correo únicamente cuando trabajo y propietario
 * mantienen habilitados los avisos.
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
    /**
     * Consulta de cuentas y su preferencia vigente de avisos por correo.
     */
    private final UserAccountStore users;
    /**
     * Publicador de solicitudes durables mediante el outbox de la transacción actual.
     */
    private final DownloadEventPublisher events;

    /**
     * Conecta difusión de estado, preferencias actuales de la cuenta y publicación durable de
     * correo.
     *
     * @param notifier Difusor de cambios de estado hacia consumidores suscritos.
     * @param users Consulta de cuentas y su preferencia vigente de avisos por correo.
     * @param events Publicador de solicitudes durables mediante el outbox de la transacción actual.
     */
    public DownloadJobNotifications(DownloadJobNotifier notifier, UserAccountStore users, DownloadEventPublisher events) {
        this.notifier = notifier;
        this.users = users;
        this.events = events;
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
            notifier.changed(view);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * Envía la instantánea solo después de que la transacción termine correctamente.
             */
            @Override
            public void afterCommit() {
                notifier.changed(view);
            }
        });
    }

    /**
     * Publica la solicitud de correo solo para una cuenta que lo pidió en el trabajo y conserva
     * habilitada su preferencia actual.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    public void requestTerminalNotification(DownloadJob job) {
        if (!job.notifyWhenReady() || job.ownerId() == null) {
            return;
        }
        users.findById(job.ownerId())
                .filter(UserAccount::notifyOnJobCompletion)
                .ifPresent(owner -> events.terminalNotificationRequested(owner, job));
    }

}

package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envuelve el procesamiento con una reserva de inbox para omitir entregas ya atendidas o reservadas
 * y permitir reintento de ejecuciones que fallan.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler
 * @see es.ubu.batchdownloader.downloadworker.ports.InboxRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
 */
public final class InboxDownloadJobHandler implements DownloadJobHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InboxDownloadJobHandler.class);
    private final InboxRepository inbox;
    private final DownloadProperties properties;
    private final DownloadJobHandler delegate;

    /**
     * Conecta reserva, duración del arrendamiento y siguiente etapa de procesamiento.
     *
     * @param inbox Reserva y deduplicación de los eventos recibidos.
     * @param properties Configuración que aporta la duración de reserva del inbox.
     * @param delegate Siguiente etapa de la cadena, que solo recibe eventos admitidos por esta
     *     política.
     */
    public InboxDownloadJobHandler(
            InboxRepository inbox,
            DownloadProperties properties,
            DownloadJobHandler delegate) {
        this.inbox = inbox;
        this.properties = properties;
        this.delegate = delegate;
    }

    /**
     * Omite eventos que no puede reservar; confirma los atendidos y libera la reserva antes de
     * propagar una RuntimeException de la etapa siguiente.
     *
     * @param event Sobre recibido con identidad y carga del trabajo solicitado o cancelado.
     */
    @Override
    public void handle(DownloadJobRequestedEvent event) {
        if (!inbox.tryStart(event.eventId(), properties.inboxLease())) {
            LOGGER.info(
                    "Ignoring duplicate download event eventId={} jobId={}",
                    event.eventId(), event.payload().jobId());
            return;
        }
        try {
            delegate.handle(event);
            inbox.complete(event.eventId());
            LOGGER.info(
                    "Download job completed eventId={} jobId={}",
                    event.eventId(), event.payload().jobId());
        } catch (RuntimeException exception) {
            inbox.release(event.eventId());
            LOGGER.warn(
                    "Download job failed and will be retried eventId={} jobId={} error={}",
                    event.eventId(),
                    event.payload().jobId(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }
}

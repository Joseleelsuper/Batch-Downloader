package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import jakarta.validation.Validator;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Procesa los eventos recibidos por {@code DownloadJobListener}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadJobListener {
    private final DownloadJobHandler handler;

    /**
     * Inicializa una instancia de {@code DownloadJobListener}.
     *
     * @param handler cadena de políticas y caso de uso que procesa el evento.
     */
    @Autowired
    public DownloadJobListener(@Qualifier("downloadJobHandler") DownloadJobHandler handler) {
        this.handler = handler;
    }

    /** Constructor público compatible que reproduce la cadena histórica del listener. */
    public DownloadJobListener(
            Validator validator,
            InboxRepository inbox,
            DownloadJobProcessor processor,
            DownloadProperties properties) {
        this(new ValidatedDownloadJobHandler(
                validator,
                new InboxDownloadJobHandler(inbox, properties, processor::process)));
    }

    /**
     * Ejecuta la operación {@code receive}.
     *
     * @param event Evento que debe procesarse.
     */
    @RabbitListener(
            queues = "${download-worker.messaging.input-queue}",
            containerFactory = "downloadRabbitListenerContainerFactory")
    public void receive(DownloadJobRequestedEvent event) {
        handler.handle(event);
    }
}

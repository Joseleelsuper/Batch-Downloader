package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/** Rechaza mensajes nulos, inválidos o incompatibles antes de ejecutar el caso de uso. */
public final class ValidatedDownloadJobHandler implements DownloadJobHandler {
    private final Validator validator;
    private final DownloadJobHandler delegate;

    /** Inicializa el wrapper de validación del contrato AMQP. */
    public ValidatedDownloadJobHandler(Validator validator, DownloadJobHandler delegate) {
        this.validator = validator;
        this.delegate = delegate;
    }

    /** {@inheritDoc} */
    @Override
    public void handle(DownloadJobRequestedEvent event) {
        if (event == null) {
            throw new AmqpRejectAndDontRequeueException("null_download_event");
        }
        Set<ConstraintViolation<DownloadJobRequestedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new AmqpRejectAndDontRequeueException("invalid_download_event");
        }
        if (!EventTypes.JOB_REQUESTED.equals(event.type())
                || event.schemaVersion() != EventTypes.CURRENT_VERSION) {
            throw new AmqpRejectAndDontRequeueException("unsupported_download_event_version");
        }
        delegate.handle(event);
    }
}

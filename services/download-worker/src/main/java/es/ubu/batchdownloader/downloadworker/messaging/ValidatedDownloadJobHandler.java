package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

/**
 * Comprueba estructura, restricciones, tipo y versión del comando antes de que pueda reservarse o
 * iniciar efectos del procesamiento.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler
 * @see es.ubu.batchdownloader.downloadworker.messaging.InboxDownloadJobHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
 */
public final class ValidatedDownloadJobHandler implements DownloadJobHandler {
    private final Validator validator;
    private final DownloadJobHandler delegate;

    /**
     * Conecta el validador declarativo y la etapa que recibirá eventos válidos.
     *
     * @param validator Validación declarativa del sobre y los elementos de la solicitud.
     * @param delegate Siguiente etapa de la cadena, que solo recibe eventos admitidos por esta
     *     política.
     */
    public ValidatedDownloadJobHandler(Validator validator, DownloadJobHandler delegate) {
        this.validator = validator;
        this.delegate = delegate;
    }

    /**
     * Rechaza mensajes null, restricciones incumplidas o contratos no soportados y delega
     * únicamente solicitudes válidas.
     *
     * @param event Sobre recibido con identidad y carga del trabajo solicitado o cancelado.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si el evento es nulo,
     *     inválido o tiene un tipo o versión incompatibles.
     */
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

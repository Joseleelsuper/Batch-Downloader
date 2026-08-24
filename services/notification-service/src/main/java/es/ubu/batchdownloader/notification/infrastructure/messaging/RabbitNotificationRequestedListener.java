package es.ubu.batchdownloader.notification.infrastructure.messaging;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import es.ubu.batchdownloader.notification.application.NotificationHandler;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import es.ubu.batchdownloader.notification.operations.NotificationWorkerHeartbeat;
import java.io.IOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Procesa los eventos recibidos por {@code RabbitNotificationRequestedListener}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class RabbitNotificationRequestedListener {

    /**
     * Estado {@code eventReader} mantenido por {@code RabbitNotificationRequestedListener}.
     */
    private final ObjectReader eventReader;
    /**
     * Dependencia {@code messageMapper} utilizada por {@code RabbitNotificationRequestedListener}.
     */
    private final NotificationRequestedMessageMapper messageMapper;
    /**
     * Estado {@code processor} mantenido por {@code RabbitNotificationRequestedListener}.
     */
    private final NotificationHandler handler;
    /** Señales operativas del consumidor. */
    private final NotificationWorkerHeartbeat heartbeat;

    /**
     * Inicializa una instancia de {@code RabbitNotificationRequestedListener}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param messageMapper Valor de {@code messageMapper} utilizado por la operación.
     * @param handler cadena de políticas que procesa la notificación.
     */
    @Autowired
    public RabbitNotificationRequestedListener(
            ObjectMapper objectMapper,
            NotificationRequestedMessageMapper messageMapper,
            NotificationHandler handler,
            NotificationWorkerHeartbeat heartbeat) {
        ObjectMapper strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        this.eventReader = strictMapper.readerFor(NotificationRequestedMessage.class);
        this.messageMapper = messageMapper;
        this.handler = handler;
        this.heartbeat = heartbeat;
    }

    /**
     * Ejecuta la operación {@code receive}.
     *
     * @param payload Carga de datos recibida por la operación.
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     */
    @RabbitListener(queues = "${notification.rabbit.queue}")
    public void receive(
            byte[] payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        try {
            NotificationRequestedMessage message = deserialize(payload);
            EmailNotification notification = messageMapper.map(message, routingKey);
            handler.handle(notification);
            heartbeat.success();
        } catch (PermanentNotificationException exception) {
            heartbeat.failure(exception);
            throw new AmqpRejectAndDontRequeueException("notification_permanently_rejected", exception);
        } catch (RuntimeException exception) {
            heartbeat.failure(exception);
            throw exception;
        }
    }

    /**
     * Ejecuta la operación {@code deserialize}.
     *
     * @param payload Carga de datos recibida por la operación.
     * @return Resultado producido por {@code deserialize}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private NotificationRequestedMessage deserialize(byte[] payload) {
        try {
            return eventReader.readValue(payload);
        } catch (IOException exception) {
            int sampleLength = Math.min(payload.length, 120);
            throw new InvalidDownloadEventException(
                    "Payload JSON inválido (muestra limitada a " + sampleLength + " bytes)", exception);
        }
    }
}

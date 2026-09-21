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
 * Recibe solicitudes de correo de RabbitMQ y las entrega al caso de uso tras validar JSON y
 * contrato.
 *
 * Rechaza explícitamente los fallos permanentes sin reencolarlos. Los demás fallos se propagan
 * a la política de reintentos, y cada resultado actualiza la señal de salud del consumidor.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
 *
 * @see es.ubu.batchdownloader.notification.application.NotificationHandler
 * @see es.ubu.batchdownloader.notification.config.NotificationRetryConfiguration
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class RabbitNotificationRequestedListener {

    /**
     * Lector inmutable del sobre que rechaza propiedades desconocidas y claves duplicadas.
     */
    private final ObjectReader eventReader;
    /**
     * Validador del contrato y conversor a la solicitud de dominio.
     */
    private final NotificationRequestedMessageMapper messageMapper;
    /**
     * Caso de uso que reserva, envía y confirma cada evento.
     */
    private final NotificationHandler handler;
    /** Señales operativas del consumidor. */
    private final NotificationWorkerHeartbeat heartbeat;

    /**
     * Crea un lector que rechaza claves duplicadas y campos desconocidos y conecta validación,
     * procesamiento y salud.
     *
     * @param objectMapper Configuración base de JSON de la que se crea un lector estricto
     *     independiente.
     *
     * @param messageMapper Validador del contrato y conversor a la solicitud de dominio.
     * @param handler Caso de uso que reserva, envía y confirma cada evento.
     * @param heartbeat Estado operativo que registra el resultado del consumidor.
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
     * Valida y procesa una entrega; confirma el avance operativo o registra el fallo antes de
     * propagarlo.
     *
     * @param payload Bytes del sobre JSON recibidos de RabbitMQ.
     * @param routingKey Clave de enrutamiento recibida de RabbitMQ.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si el procesamiento
     *     comunica un fallo permanente.
     *
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     el JSON o el contrato de entrada no son válidos.
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
     * Lee el sobre con detección estricta de duplicados y campos desconocidos, sin registrar su
     * contenido.
     *
     * @param payload Bytes del sobre JSON recibidos de RabbitMQ.
     * @return sobre todavía pendiente de validación funcional.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     Jackson no puede leer los bytes conforme al esquema del mensaje.
     */
    private NotificationRequestedMessage deserialize(byte[] payload) {
        try {
            return eventReader.readValue(payload);
        } catch (IOException exception) {
            int sampleLength = Math.min(payload.length, 120);
            throw new InvalidNotificationEventException(
                    "Payload JSON inválido (muestra limitada a " + sampleLength + " bytes)", exception);
        }
    }
}

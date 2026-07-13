package es.ubu.batchdownloader.notification.infrastructure.messaging;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import es.ubu.batchdownloader.notification.application.ProcessEmailNotification;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.io.IOException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class RabbitNotificationRequestedListener {

    private final ObjectReader eventReader;
    private final NotificationRequestedMessageMapper messageMapper;
    private final ProcessEmailNotification processor;

    public RabbitNotificationRequestedListener(
            ObjectMapper objectMapper,
            NotificationRequestedMessageMapper messageMapper,
            ProcessEmailNotification processor) {
        ObjectMapper strictMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        this.eventReader = strictMapper.readerFor(NotificationRequestedMessage.class);
        this.messageMapper = messageMapper;
        this.processor = processor;
    }

    @RabbitListener(queues = "${notification.rabbit.queue}")
    public void receive(
            byte[] payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        NotificationRequestedMessage message = deserialize(payload);
        EmailNotification notification = messageMapper.map(message, routingKey);
        processor.execute(notification);
    }

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

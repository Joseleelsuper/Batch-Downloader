package es.ubu.batchdownloader.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Elimina el token cifrado del outbox una vez confirmado por RabbitMQ. */
@Component
class OutboxPayloadSanitizer {
    private final ObjectMapper mapper;

    OutboxPayloadSanitizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String afterPublish(String eventType, String payload) {
        if (!"notification.email.requested".equals(eventType)) return payload;
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode body = root.path("payload");
            String template = body.path("template").asText();
            if (!"EMAIL_VERIFICATION".equals(template) && !"PASSWORD_RESET".equals(template)) {
                return payload;
            }
            JsonNode parameters = body.path("parameters");
            if (parameters instanceof ObjectNode object) {
                object.remove("token");
                object.put("deliveryTokenPurged", true);
            }
            return mapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("outbox_sensitive_payload_purge_failed", exception);
        }
    }
}

package es.ubu.batchdownloader.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Retira el token cifrado de correos de acceso una vez confirmado el envío al
 * broker, conservando el resto del sobre para retención y diagnóstico.
 *
 * @see es.ubu.batchdownloader.messaging.OutboxDispatcher
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
@Component
class OutboxPayloadSanitizer {
    private final ObjectMapper mapper;

    /**
     * Conecta el lector y escritor JSON utilizado para retirar datos de entrega.
     *
     * @param mapper Serializador utilizado para retirar tokens de eventos ya confirmados.
     */
    OutboxPayloadSanitizer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Para la plantilla de enlace mágico elimina parameters.token y marca deliveryTokenPurged;
     * otros eventos o plantillas conservan su contenido.
     *
     * @param eventType Tipo de evento que identifica su contrato de carga.
     * @param payload Sobre JSON persistido o carga del evento antes de envolverla, según el punto
     *     del flujo.
     * @return sobre saneado o el original cuando no requiere retirada.
     * @throws IllegalStateException si no puede leerse o serializarse el sobre de notificación.
     */
    String afterPublish(String eventType, String payload) {
        if (!"notification.email.requested".equals(eventType)) return payload;
        try {
            JsonNode root = mapper.readTree(payload);
            JsonNode body = root.path("payload");
            String template = body.path("template").asText();
            if (!"MAGIC_LINK".equals(template)) {
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

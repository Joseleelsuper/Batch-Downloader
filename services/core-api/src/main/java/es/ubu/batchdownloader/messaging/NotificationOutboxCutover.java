package es.ubu.batchdownloader.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Migra los tokens de correo todavía pendientes a enc:v1 antes de habilitar la publicación del
 * outbox y comprueba que los sobres ya cifrados pueden descifrarse.
 *
 * @see es.ubu.batchdownloader.messaging.OutboxDispatcher
 * @see es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotificationOutboxCutover implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxCutover.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final NotificationTokenEnvelope tokens;
    private final TransactionTemplate transactions;
    /**
     * True únicamente después del corte satisfactorio.
     */
    private final AtomicBoolean completed = new AtomicBoolean();

    /**
     * Conecta la reserva de eventos, lectura JSON y cifrado con una transacción de arranque.
     *
     * @param repository Persistencia del outbox que participa en la transacción vigente.
     * @param objectMapper Serializador del sobre de eventos y sus cargas JSON.
     * @param tokens Cifrador compartido con el consumidor de correo para sobres enc:v1.
     * @param transactions Plantilla que delimita las transacciones cortas de migración, reserva o
     *     confirmación.
     */
    NotificationOutboxCutover(
            OutboxEventRepository repository,
            ObjectMapper objectMapper,
            NotificationTokenEnvelope tokens,
            TransactionTemplate transactions) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.tokens = tokens;
        this.transactions = transactions;
    }

    /**
     * Migra los eventos pendientes en una transacción y abre la barrera de publicación solo después
     * de confirmar; un fallo impide dar el corte por terminado.
     *
     * @param arguments Argumentos de arranque; el corte de tokens no depende de ellos.
     * @throws IllegalStateException si un evento no tiene objeto JSON, parámetros o token válidos.
     */
    @Override
    public void run(ApplicationArguments arguments) {
        Integer migrated = transactions.execute(status -> migratePendingEvents());
        completed.set(true);
        LOGGER.info("Corte enc:v1 del outbox completado migrated={}", migrated == null ? 0 : migrated);
    }

    /**
     * Indica si terminó y se confirmó la migración inicial del outbox de notificaciones.
     *
     * @return true únicamente después del corte satisfactorio.
     */
    boolean completed() {
        return completed.get();
    }

    /**
     * Bloquea solicitudes de correo pendientes, cifra tokens antiguos de verificación o
     * recuperación y verifica los que ya usan enc:v1; omite otras plantillas.
     *
     * @return cantidad de eventos cuyo token se transformó.
     * @throws IllegalStateException si una solicitud de identidad carece de parámetros o token
     *     textual.
     */
    private int migratePendingEvents() {
        int migrated = 0;
        for (OutboxEventEntity event : repository.findPendingNotificationRequestsForUpdate()) {
            ObjectNode root = parseObject(event);
            JsonNode payload = root.path("payload");
            String template = payload.path("template").asText();
            if (!"EMAIL_VERIFICATION".equals(template) && !"PASSWORD_RESET".equals(template)) {
                continue;
            }
            JsonNode parametersNode = payload.path("parameters");
            if (!(parametersNode instanceof ObjectNode parameters)) {
                throw invalid(event, "parameters_missing");
            }
            JsonNode tokenNode = parameters.get("token");
            if (tokenNode == null || !tokenNode.isTextual() || tokenNode.asText().isBlank()) {
                throw invalid(event, "token_missing");
            }
            String value = tokenNode.asText();
            if (NotificationTokenEnvelope.isVersion1(value)) {
                tokens.decrypt(value);
                continue;
            }
            parameters.put("token", tokens.encrypt(value));
            event.replacePayload(write(root, event));
            repository.save(event);
            migrated++;
        }
        return migrated;
    }

    /**
     * Lee el sobre persistido y exige que la raíz JSON sea un objeto antes de modificar el token.
     *
     * @param event Evento pendiente o copia reclamada cuyo contenido se procesa.
     * @return objeto raíz del evento.
     * @throws IllegalStateException si el JSON no se puede leer o su raíz no es un objeto.
     */
    private ObjectNode parseObject(OutboxEventEntity event) {
        try {
            JsonNode root = objectMapper.readTree(event.payload());
            if (root instanceof ObjectNode object) return object;
            throw invalid(event, "payload_not_object");
        } catch (JsonProcessingException exception) {
            throw invalid(event, "payload_invalid", exception);
        }
    }

    /**
     * Serializa el sobre modificado sin incluir su contenido en los diagnósticos de fallo.
     *
     * @param root Objeto JSON del sobre de evento con el token ya cifrado.
     * @param event Evento pendiente o copia reclamada cuyo contenido se procesa.
     * @return JSON que sustituye al evento pendiente.
     * @throws IllegalStateException si el sobre no se puede serializar.
     */
    private String write(ObjectNode root, OutboxEventEntity event) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw invalid(event, "payload_not_serializable", exception);
        }
    }

    /**
     * Construye un fallo de arranque que identifica el evento y la regla incumplida sin revelar su
     * carga.
     *
     * @param event Evento pendiente o copia reclamada cuyo contenido se procesa.
     * @param reason Código interno del incumplimiento, sin incluir contenido ni tokens.
     * @return excepción con identidad del evento, motivo y causa cuando se conoce.
     */
    private IllegalStateException invalid(OutboxEventEntity event, String reason) {
        return invalid(event, reason, null);
    }

    /**
     * Construye un fallo de arranque que identifica el evento y la regla incumplida sin revelar su
     * carga.
     *
     * @param event Evento pendiente o copia reclamada cuyo contenido se procesa.
     * @param reason Código interno del incumplimiento, sin incluir contenido ni tokens.
     * @param cause Fallo original de lectura o escritura; null si se detectó una estructura
     *     inválida.
     * @return excepción con identidad del evento, motivo y causa cuando se conoce.
     */
    private IllegalStateException invalid(
            OutboxEventEntity event, String reason, Exception cause) {
        return new IllegalStateException(
                "notification_outbox_cutover_invalid_event:" + event.id() + ":" + reason,
                cause);
    }
}

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

/** Migra el outbox pendiente a {@code enc:v1} antes de permitir su publicación. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotificationOutboxCutover implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxCutover.class);

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final NotificationTokenEnvelope tokens;
    private final TransactionTemplate transactions;
    private final AtomicBoolean completed = new AtomicBoolean();

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

    @Override
    public void run(ApplicationArguments arguments) {
        Integer migrated = transactions.execute(status -> migratePendingEvents());
        completed.set(true);
        LOGGER.info("Corte enc:v1 del outbox completado migrated={}", migrated == null ? 0 : migrated);
    }

    boolean completed() {
        return completed.get();
    }

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

    private ObjectNode parseObject(OutboxEventEntity event) {
        try {
            JsonNode root = objectMapper.readTree(event.payload());
            if (root instanceof ObjectNode object) return object;
            throw invalid(event, "payload_not_object");
        } catch (JsonProcessingException exception) {
            throw invalid(event, "payload_invalid", exception);
        }
    }

    private String write(ObjectNode root, OutboxEventEntity event) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw invalid(event, "payload_not_serializable", exception);
        }
    }

    private IllegalStateException invalid(OutboxEventEntity event, String reason) {
        return invalid(event, reason, null);
    }

    private IllegalStateException invalid(
            OutboxEventEntity event, String reason, Exception cause) {
        return new IllegalStateException(
                "notification_outbox_cutover_invalid_event:" + event.id() + ":" + reason,
                cause);
    }
}

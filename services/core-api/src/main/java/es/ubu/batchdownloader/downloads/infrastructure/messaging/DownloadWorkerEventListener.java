package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procesa los eventos recibidos por {@code DownloadWorkerEventListener}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadWorkerEventListener {
    /**
     * Dependencia {@code objectMapper} utilizada por {@code DownloadWorkerEventListener}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code jdbc} mantenido por {@code DownloadWorkerEventListener}.
     */
    private final JdbcTemplate jdbc;
    /**
     * Estado {@code jobs} mantenido por {@code DownloadWorkerEventListener}.
     */
    private final DownloadJobService jobs;
    /**
     * Estado {@code clock} mantenido por {@code DownloadWorkerEventListener}.
     */
    private final Clock clock;

    /**
     * Inicializa una instancia de {@code DownloadWorkerEventListener}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param jobs Valor de {@code jobs} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     */
    public DownloadWorkerEventListener(
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            DownloadJobService jobs,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.clock = clock;
    }

    /**
     * Ejecuta la operación {@code receive}.
     *
     * @param message Mensaje que debe procesarse.
     */
    @RabbitListener(queues = "${app.messaging.download-events-queue}")
    @Transactional
    public void receive(Message message) {
        WorkerEvent event = parse(message);
        if (!claim(event.eventId(), event.type())) {
            return;
        }
        apply(event);
        jdbc.update(
                "UPDATE core_inbox_messages SET processed_at = ? WHERE message_id = ?",
                java.sql.Timestamp.from(clock.instant()), event.eventId().toString());
    }

    /**
     * Analiza el contenido recibido mediante {@code parse}.
     *
     * @param message Mensaje que debe procesarse.
     * @return Resultado producido por {@code parse}.
     */
    private WorkerEvent parse(Message message) {
        try {
            JsonNode envelope = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            UUID eventId = uuid(envelope, "eventId");
            String type = text(envelope, "type");
            if (envelope.path("schemaVersion").asInt(-1) != 1 || !isWorkerEvent(type)) {
                throw invalid("unsupported_download_event");
            }
            return new WorkerEvent(eventId, type, envelope.path("payload"));
        } catch (AmqpRejectAndDontRequeueException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("invalid_download_event");
        }
    }

    /**
     * Ejecuta la operación {@code apply}.
     *
     * @param event Evento que debe procesarse.
     */
    private void apply(WorkerEvent event) {
        JsonNode payload = event.payload();
        switch (event.type()) {
            case "download.job.progressed" -> applyProgress(payload);
            case "download.job.ready" -> applyReady(payload);
            case "download.job.failed" -> jobs.applyFailed(
                    uuid(payload, "jobId"), text(payload, "errorCode"));
            default -> throw invalid("unsupported_download_event");
        }
    }

    /**
     * Ejecuta la operación {@code applyProgress}.
     *
     * @param payload Carga de datos recibida por la operación.
     */
    private void applyProgress(JsonNode payload) {
        String value = text(payload, "status");
        DownloadItemStatus status;
        try {
            status = DownloadItemStatus.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_download_item_status");
        }
        jobs.applyProgress(
                uuid(payload, "jobId"),
                uuid(payload, "itemId"),
                status,
                payload.path("bytesDownloaded").asLong(0),
                optionalText(payload, "sha256"),
                optionalText(payload, "errorCode"));
    }

    /**
     * Ejecuta la operación {@code applyReady}.
     *
     * @param payload Carga de datos recibida por la operación.
     */
    private void applyReady(JsonNode payload) {
        DownloadJobStatus status;
        Instant expiresAt;
        try {
            status = DownloadJobStatus.valueOf(text(payload, "status").toUpperCase(Locale.ROOT));
            expiresAt = Instant.parse(text(payload, "expiresAt"));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_download_job_status");
        }
        jobs.applyReady(uuid(payload, "jobId"), status, text(payload, "objectKey"), expiresAt);
    }

    /**
     * Reserva el elemento solicitado mediante {@code claim}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    private boolean claim(UUID eventId, String type) {
        return jdbc.update(
                "INSERT IGNORE INTO core_inbox_messages (message_id, message_type, received_at) VALUES (?, ?, ?)",
                eventId.toString(), type, java.sql.Timestamp.from(clock.instant())) == 1;
    }

    /**
     * Indica si se cumple la condición mediante {@code isWorkerEvent}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    private boolean isWorkerEvent(String type) {
        return "download.job.progressed".equals(type)
                || "download.job.ready".equals(type)
                || "download.job.failed".equals(type);
    }

    /**
     * Ejecuta la operación {@code uuid}.
     *
     * @param object Valor de {@code object} utilizado por la operación.
     * @param field Valor de {@code field} utilizado por la operación.
     * @return Resultado producido por {@code uuid}.
     */
    private UUID uuid(JsonNode object, String field) {
        try {
            return UUID.fromString(text(object, field));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_download_event_identifier");
        }
    }

    /**
     * Ejecuta la operación {@code text}.
     *
     * @param object Valor de {@code object} utilizado por la operación.
     * @param field Valor de {@code field} utilizado por la operación.
     * @return Resultado producido por {@code text}.
     */
    private String text(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null || value.isBlank()) {
            throw invalid("missing_download_event_" + field);
        }
        return value;
    }

    /**
     * Ejecuta la operación {@code optionalText}.
     *
     * @param object Valor de {@code object} utilizado por la operación.
     * @param field Valor de {@code field} utilizado por la operación.
     * @return Resultado producido por {@code optionalText}.
     */
    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /**
     * Ejecuta la operación {@code invalid}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @return Resultado producido por {@code invalid}.
     */
    private AmqpRejectAndDontRequeueException invalid(String code) {
        return new AmqpRejectAndDontRequeueException(code);
    }

    /**
     * Representa los datos inmutables de {@code WorkerEvent}.
     *
     * @param eventId Valor de {@code eventId} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param payload Valor de {@code payload} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record WorkerEvent(UUID eventId, String type, JsonNode payload) {}
}

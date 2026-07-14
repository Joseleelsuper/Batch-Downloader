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

/** Applies versioned worker events exactly once before notifying SSE subscribers. */
@Component
public class DownloadWorkerEventListener {
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final DownloadJobService jobs;
    private final Clock clock;

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
     * Deliberately lets database and domain failures escape. The listener
     * container can then apply its configured retry/DLQ policy and the inbox
     * insertion rolls back with the transaction. Only malformed envelopes are
     * rejected without a retry in {@link #parse(Message)}.
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

    private boolean claim(UUID eventId, String type) {
        return jdbc.update(
                "INSERT IGNORE INTO core_inbox_messages (message_id, message_type, received_at) VALUES (?, ?, ?)",
                eventId.toString(), type, java.sql.Timestamp.from(clock.instant())) == 1;
    }

    private boolean isWorkerEvent(String type) {
        return "download.job.progressed".equals(type)
                || "download.job.ready".equals(type)
                || "download.job.failed".equals(type);
    }

    private UUID uuid(JsonNode object, String field) {
        try {
            return UUID.fromString(text(object, field));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_download_event_identifier");
        }
    }

    private String text(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null || value.isBlank()) {
            throw invalid("missing_download_event_" + field);
        }
        return value;
    }

    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private AmqpRejectAndDontRequeueException invalid(String code) {
        return new AmqpRejectAndDontRequeueException(code);
    }

    private record WorkerEvent(UUID eventId, String type, JsonNode payload) {}
}

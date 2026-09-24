package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloads.application.DownloadJobEventHandler;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
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
 * Valida y deduplica eventos del worker y confirma su aplicación y el inbox en una misma
 * transacción.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobEventHandler
 * @see es.ubu.batchdownloader.downloads.infrastructure.messaging.DownloadOutboxPublisher
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
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
    private final DownloadJobEventHandler jobs;
    /**
     * Estado {@code clock} mantenido por {@code DownloadWorkerEventListener}.
     */
    private final Clock clock;
    private final DownloadStorageCoordinator storage;

    /**
     * Conecta lectura del sobre, reserva SQL del inbox y aplicación transaccional de los cambios.
     *
     * @param objectMapper Conversor JSON del sobre y la carga útil recibidos de RabbitMQ.
     * @param jdbc Acceso SQL que participa en la transacción de Spring del llamador.
     * @param jobs Caso de uso que aplica los eventos del worker al agregado de descarga.
     * @param clock Reloj que determina cuotas, cambios de estado y vencimientos.
     */
    public DownloadWorkerEventListener(
            ObjectMapper objectMapper,
            JdbcTemplate jdbc,
            DownloadJobEventHandler jobs,
            Clock clock,
            DownloadStorageCoordinator storage) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.clock = clock;
        this.storage = storage;
    }

    /**
     * Ignora eventos ya reservados y aplica una sola vez los nuevos antes de marcar el inbox como
     * procesado; un fallo revierte la transacción.
     *
     * @param message Mensaje RabbitMQ con un sobre JSON UTF-8 de versión 1.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si el sobre, el tipo o sus
     *     campos obligatorios no son válidos.
     */
    @RabbitListener(queues = "${app.messaging.download-events-queue}")
    @Transactional
    public void receive(Message message) {
        WorkerEvent event = parse(message);
        if (!storage.acceptsEvent(uuid(event.payload(), "jobId"), event.attemptId(), event.type())) return;
        if (!claim(event.eventId(), event.type())) {
            return;
        }
        apply(event);
        jdbc.update(
                "UPDATE core_inbox_messages SET processed_at = ? WHERE message_id = ?",
                java.sql.Timestamp.from(clock.instant()), event.eventId().toString());
    }

    /**
     * Lee el sobre UTF-8, exige versión 1 y admite únicamente los cuatro eventos publicados por el
     * worker.
     *
     * @param message Mensaje RabbitMQ con un sobre JSON UTF-8 de versión 1.
     * @return sobre con UUID, tipo y carga útil.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si el JSON o el sobre no
     *     se pueden interpretar.
     */
    private WorkerEvent parse(Message message) {
        try {
            JsonNode envelope = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            UUID eventId = uuid(envelope, "eventId");
            String type = text(envelope, "type");
            if (envelope.path("schemaVersion").asInt(-1) != 1 || !isWorkerEvent(type)) {
                throw invalid("unsupported_download_event");
            }
            return new WorkerEvent(eventId, type, envelope.path("payload"), optionalText(envelope, "causationId"));
        } catch (AmqpRejectAndDontRequeueException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("invalid_download_event");
        }
    }

    /**
     * Dirige la carga útil al caso de uso de progreso, resultado, aplazamiento o fallo
     * correspondiente.
     *
     * @param event Sobre validado cuya reserva del inbox pertenece a esta transacción.
     */
    private void apply(WorkerEvent event) {
        JsonNode payload = event.payload();
        switch (event.type()) {
            case "download.job.progressed" -> applyProgress(payload);
            case "download.job.ready" -> applyReady(payload);
            case "download.job.deferred" -> applyDeferred(payload);
            case "download.job.failed" -> jobs.applyFailed(
                    uuid(payload, "jobId"), text(payload, "errorCode"));
            default -> throw invalid("unsupported_download_event");
        }
    }

    /**
     * Valida el estado del elemento y aplica bytes, hash y fallo opcionales al trabajo indicado.
     *
     * @param payload Campos del evento de descarga dentro del sobre validado.
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
     * Exige tamaño y hash juntos cuando existen, aplica el resultado descargable y registra los
     * elementos completados en el historial de cuentas.
     *
     * @param payload Campos del evento de descarga dentro del sobre validado.
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
        UUID jobId = uuid(payload, "jobId");
        String objectKey = text(payload, "objectKey");
        Long sizeBytes = nullableLong(payload, "sizeBytes");
        String sha256 = optionalText(payload, "sha256");
        if ((sizeBytes == null) != (sha256 == null)) {
            throw invalid("invalid_download_artifact_metadata");
        }
        Long storageBytes = nullableLong(payload, "storageBytes");
        if (storageBytes != null && (storageBytes <= 0 || sizeBytes != null && storageBytes < sizeBytes)) {
            throw invalid("invalid_download_storage_metadata");
        }
        storage.prepared(jobId, storageBytes);
        if (sizeBytes == null && sha256 == null) {
            jobs.applyReady(jobId, status, objectKey, expiresAt);
        } else {
            jobs.applyReady(jobId, status, objectKey, sizeBytes, sha256, expiresAt);
        }
        recordAuthenticatedHistory(jobId);
    }

    /**
     * Interpreta el instante del siguiente intento y comunica el motivo temporal de espera al
     * trabajo.
     *
     * @param payload Campos del evento de descarga dentro del sobre validado.
     */
    private void applyDeferred(JsonNode payload) {
        Instant retryAt;
        try {
            retryAt = Instant.parse(text(payload, "retryAt"));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_download_retry_at");
        }
        jobs.applyDeferred(uuid(payload, "jobId"), text(payload, "waitReason"), retryAt);
    }

    /**
     * Inserta sin duplicar el historial de elementos completados únicamente cuando el trabajo tiene
     * propietario autenticado.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     */
    private void recordAuthenticatedHistory(UUID jobId) {
        jdbc.update(
                """
                INSERT IGNORE INTO user_download_history
                    (id, user_id, job_id, app_id, app_name, downloaded_at)
                SELECT UUID_TO_BIN(UUID()), job.owner_id, job.id, UUID_TO_BIN(item.app_id),
                       COALESCE(NULLIF(item.app_name, ''), item.app_id), ?
                FROM download_jobs job
                JOIN download_job_items item ON item.job_id = job.id
                WHERE job.id = ?
                  AND job.owner_id IS NOT NULL
                  AND item.status = 'COMPLETED'
                """,
                java.sql.Timestamp.from(clock.instant()), jobId.toString());
    }

    /**
     * Intenta reservar el UUID del evento con una inserción idempotente dentro de la transacción
     * vigente.
     *
     * @param eventId UUID del evento, utilizado como clave de deduplicación del inbox.
     * @param type Tipo de evento de progreso, finalización, aplazamiento o fallo del worker.
     * @return true únicamente si esta llamada insertó la reserva.
     */
    private boolean claim(UUID eventId, String type) {
        return jdbc.update(
                "INSERT IGNORE INTO core_inbox_messages (message_id, message_type, received_at) VALUES (?, ?, ?)",
                eventId.toString(), type, java.sql.Timestamp.from(clock.instant())) == 1;
    }

    /**
     * Comprueba pertenencia a los tipos de evento que Core acepta del worker.
     *
     * @param type Tipo de evento de progreso, finalización, aplazamiento o fallo del worker.
     * @return true para progressed, ready, deferred o failed.
     */
    private boolean isWorkerEvent(String type) {
        return "download.job.progressed".equals(type)
                || "download.job.ready".equals(type)
                || "download.job.deferred".equals(type)
                || "download.job.failed".equals(type);
    }

    /**
     * Lee un entero opcional y rechaza valores presentes que Jackson no puede convertir a long.
     *
     * @param object Objeto JSON del que se lee un campo de la carga útil.
     * @param field Nombre del campo requerido u opcional dentro del objeto JSON.
     * @return valor numérico o null si el campo falta o es JSON null.
     */
    private Long nullableLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.canConvertToLong()) throw invalid("invalid_download_event_" + field);
        return value.longValue();
    }

    /**
     * Exige un campo textual con un UUID válido antes de usarlo como identidad del evento o
     * agregado.
     *
     * @param object Objeto JSON del que se lee un campo de la carga útil.
     * @param field Nombre del campo requerido u opcional dentro del objeto JSON.
     * @return UUID interpretado.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si falta el campo o el
     *     identificador es inválido.
     */
    private UUID uuid(JsonNode object, String field) {
        try {
            return UUID.fromString(text(object, field));
        } catch (IllegalArgumentException exception) {
            throw invalid("invalid_download_event_identifier");
        }
    }

    /**
     * Exige un campo textual no vacío de la carga útil.
     *
     * @param object Objeto JSON del que se lee un campo de la carga útil.
     * @param field Nombre del campo requerido u opcional dentro del objeto JSON.
     * @return texto original, sin recortarlo.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si el campo falta, no es
     *     texto o está en blanco.
     */
    private String text(JsonNode object, String field) {
        String value = optionalText(object, field);
        if (value == null || value.isBlank()) {
            throw invalid("missing_download_event_" + field);
        }
        return value;
    }

    /**
     * Lee un campo únicamente cuando su valor JSON es textual.
     *
     * @param object Objeto JSON del que se lee un campo de la carga útil.
     * @param field Nombre del campo requerido u opcional dentro del objeto JSON.
     * @return texto presente o null para cualquier otro tipo o ausencia.
     */
    private String optionalText(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /**
     * Crea un rechazo permanente del mensaje para que RabbitMQ no lo reencole.
     *
     * @param code Código de validación seguro que identifica el defecto del evento.
     * @return excepción de rechazo sin reencolado.
     */
    private AmqpRejectAndDontRequeueException invalid(String code) {
        return new AmqpRejectAndDontRequeueException(code);
    }

    /**
     * Transporta la identidad deduplicable y la carga útil de un sobre ya validado.
     *
     * @param eventId UUID del evento, utilizado como clave de deduplicación del inbox.
     * @param type Tipo de evento de progreso, finalización, aplazamiento o fallo del worker.
     * @param payload Campos del evento de descarga dentro del sobre validado.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    private record WorkerEvent(UUID eventId, String type, JsonNode payload, String attemptId) {}
}

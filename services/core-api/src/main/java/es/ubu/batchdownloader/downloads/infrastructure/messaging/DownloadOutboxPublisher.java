package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Serializa las solicitudes de descarga, cancelación y correo en el outbox para confirmarlas junto
 * al trabajo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher
 * @see es.ubu.batchdownloader.messaging.OutboxWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Component
class DownloadOutboxPublisher implements DownloadEventPublisher {
    /**
     * Estado {@code outbox} mantenido por {@code DownloadOutboxPublisher}.
     */
    private final OutboxWriter outbox;

    /**
     * Conecta el escritor del outbox que conserva la atomicidad entre trabajo y solicitudes.
     *
     * @param outbox Escritor de eventos durables que participa en la transacción actual.
     */
    DownloadOutboxPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /**
     * Incluye los UUID de trabajo, elementos, aplicaciones y fuentes exactas en
     * download.job.requested.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    @Override
    public void jobRequested(DownloadJob job) {
        var items = job.items().stream().map(item -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("itemId", item.id());
            value.put("appId", item.appId());
            value.put("sourceRef", item.sourceRef());
            return value;
        }).toList();
        outbox.append(
                "download-job", job.id(), "download.job.requested",
                "download.job.requested", job.id(), null,
                Map.of(
                        "jobId", job.id(),
                        "items", items));
    }

    /**
     * Registra download.job.cancel-requested con el UUID del trabajo para su parada cooperativa.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    @Override
    public void cancellationRequested(DownloadJob job) {
        outbox.append(
                "download-job", job.id(), "download.job.cancel-requested",
                "download.job.cancel-requested", job.id(), null,
                Map.of("jobId", job.id()));
    }

    /**
     * Elige DOWNLOAD_READY para resultados descargables y DOWNLOAD_FAILED para fallos; conserva
     * vencimiento o código de error seguro en los parámetros del correo.
     *
     * @param owner Cuenta que recibirá el mensaje en su dirección de correo actual.
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    @Override
    public void terminalNotificationRequested(UserAccount owner, DownloadJob job) {
        boolean downloadable = job.status().downloadable();
        String template = downloadable ? "DOWNLOAD_READY" : "DOWNLOAD_FAILED";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("jobId", job.id().toString());
        if (downloadable) {
            parameters.put("expiresAt", job.expiresAt().toString());
        } else {
            String code = job.failureCode() == null ? "download_failed" : job.failureCode();
            parameters.put("failureCode", code);
            parameters.put("failureMessage", "No se pudo preparar el paquete solicitado.");
        }
        outbox.append(
                "download-job", job.id(), "notification.email.requested",
                "notification.email.requested", job.id(), null,
                Map.of("recipient", owner.email(), "template", template, "parameters", parameters));
    }
}

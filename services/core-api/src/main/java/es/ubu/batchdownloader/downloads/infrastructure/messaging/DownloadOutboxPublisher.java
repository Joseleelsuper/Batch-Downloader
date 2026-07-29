package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class DownloadOutboxPublisher implements DownloadEventPublisher {
    private final OutboxWriter outbox;

    DownloadOutboxPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

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

    @Override
    public void cancellationRequested(DownloadJob job) {
        outbox.append(
                "download-job", job.id(), "download.job.cancel-requested",
                "download.job.cancel-requested", job.id(), null,
                Map.of("jobId", job.id()));
    }

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

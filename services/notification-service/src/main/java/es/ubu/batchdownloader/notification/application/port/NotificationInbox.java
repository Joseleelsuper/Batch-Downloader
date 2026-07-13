package es.ubu.batchdownloader.notification.application.port;

import java.util.UUID;

public interface NotificationInbox {

    ClaimResult claim(UUID eventId, String eventType);

    void markProcessed(UUID eventId);

    void markFailed(UUID eventId, String error);

    enum ClaimResult {
        ACQUIRED,
        ALREADY_PROCESSED,
        BUSY
    }
}

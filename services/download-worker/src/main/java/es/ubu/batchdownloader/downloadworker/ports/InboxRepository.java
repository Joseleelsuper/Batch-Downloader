package es.ubu.batchdownloader.downloadworker.ports;

import java.time.Duration;
import java.util.UUID;

public interface InboxRepository {
    boolean tryStart(UUID eventId, Duration lease);

    void complete(UUID eventId);

    void release(UUID eventId);
}

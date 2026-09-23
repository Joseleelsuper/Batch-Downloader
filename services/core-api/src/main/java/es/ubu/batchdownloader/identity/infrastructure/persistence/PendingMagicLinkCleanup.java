package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.PendingMagicLinkStore;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Elimina solicitudes vencidas o consumidas con una cadencia configurable. */
@Component
class PendingMagicLinkCleanup {
    private final PendingMagicLinkStore requests;
    private final Clock clock;

    PendingMagicLinkCleanup(PendingMagicLinkStore requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.auth.pending-magic-link-cleanup-interval:PT1M}")
    void clean() {
        requests.deleteExpiredOrConsumed(clock.instant());
    }
}

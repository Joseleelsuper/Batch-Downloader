package es.ubu.batchdownloader.identity.application.port;

import java.util.UUID;

/** Invalida todas las sesiones persistentes de una cuenta. */
public interface AccountSessionInvalidator {
    void invalidateAll(UUID userId);
}

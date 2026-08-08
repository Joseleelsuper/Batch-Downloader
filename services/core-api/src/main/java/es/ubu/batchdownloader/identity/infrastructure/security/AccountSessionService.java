package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Invalida sesiones JDBC por el principal nuevo o por su nombre heredado. */
@Component
public class AccountSessionService implements AccountSessionInvalidator {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public void invalidateAll(UUID userId, String legacyUsername) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(sessions.findByPrincipalName(userId.toString()).keySet());
        if (legacyUsername != null && !legacyUsername.isBlank()) {
            ids.addAll(sessions.findByPrincipalName(legacyUsername).keySet());
        }
        ids.forEach(sessions::deleteById);
    }
}

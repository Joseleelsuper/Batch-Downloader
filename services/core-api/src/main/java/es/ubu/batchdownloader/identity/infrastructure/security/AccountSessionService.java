package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Invalida sesiones JDBC mediante el UUID canónico del principal. */
@Component
public class AccountSessionService implements AccountSessionInvalidator {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    @Override
    public void invalidateAll(UUID userId) {
        sessions.findByPrincipalName(userId.toString()).keySet().forEach(sessions::deleteById);
    }
}

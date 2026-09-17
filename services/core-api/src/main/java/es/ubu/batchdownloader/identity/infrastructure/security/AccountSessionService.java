package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator;
import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Revoca sesiones indexadas por UUID después de un cambio de credenciales, sin depender del nombre
 * visible de la cuenta.
 *
 * @see es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Component
public class AccountSessionService implements AccountSessionInvalidator {
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    /**
     * Conecta el repositorio de sesiones que permite localizar todas las de un principal.
     *
     * @param sessions Repositorio de Spring Session indexado por el UUID textual de principal.
     */
    public AccountSessionService(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * Localiza las sesiones del UUID y elimina cada una del repositorio para impedir su
     * reutilización.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     */
    @Override
    public void invalidateAll(UUID userId) {
        sessions.findByPrincipalName(userId.toString()).keySet().forEach(sessions::deleteById);
    }
}

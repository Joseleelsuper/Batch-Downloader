package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.identity.application.port.OauthIdentityStore;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.OauthIdentity;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Resuelve una identidad Google sin usar el correo como identificador estable. */
@Service
public class GoogleOauthAccountService {
    private final UserAccountStore users;
    private final OauthIdentityStore identities;
    private final IdentityService identityService;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public GoogleOauthAccountService(
            UserAccountStore users,
            OauthIdentityStore identities,
            IdentityService identityService,
            Clock clock,
            TransactionTemplate transactions) {
        this.users = users;
        this.identities = identities;
        this.identityService = identityService;
        this.clock = clock;
        this.transactions = transactions;
    }

    public UserAccount resolve(String subject, String email, boolean emailVerified) {
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw new OauthLoginException("oauth_claims_invalid");
        }
        if (!emailVerified) throw new OauthLoginException("oauth_email_not_verified");

        Instant now = clock.instant();
        OauthIdentity existingIdentity = identities
                .findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, subject)
                .orElse(null);
        if (existingIdentity != null) {
            UserAccount updated = transactions.execute(status -> {
                OauthIdentity current = identities
                        .findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, subject)
                        .orElseThrow(() -> new OauthLoginException("oauth_account_unavailable"));
                UserAccount account = requireUser(current.userId());
                current.recordLogin(email, now);
                identities.save(current);
                return account;
            });
            if (updated == null) throw new OauthLoginException("oauth_account_unavailable");
            return updated;
        }

        UserAccount account = users.findByNormalizedEmail(IdentityService.normalize(email)).orElse(null);
        if (account == null) account = identityService.createOauthAccount(email);
        java.util.UUID accountId = requireUser(account.id()).id();

        try {
            UserAccount linked = transactions.execute(status -> {
                UserAccount current = requireUser(accountId);
                if (identities.existsByUserIdAndProvider(
                        current.id(), OauthIdentity.Provider.GOOGLE)) {
                    throw new OauthLoginException("oauth_account_conflict");
                }
                if (!current.emailVerified()) current = identityService.markVerified(current.id());
                identities.save(OauthIdentity.link(
                        current.id(), OauthIdentity.Provider.GOOGLE, subject, email, now));
                return current;
            });
            if (linked == null) throw new OauthLoginException("oauth_account_unavailable");
            return linked;
        } catch (DataIntegrityViolationException race) {
            // La consulta de recuperación se ejecuta fuera de la transacción fallida.
            OauthIdentity winner = identities
                    .findByProviderAndSubject(OauthIdentity.Provider.GOOGLE, subject)
                    .orElseThrow(() -> new OauthLoginException("oauth_account_conflict"));
            return requireUser(winner.userId());
        }
    }

    private UserAccount requireUser(java.util.UUID userId) {
        UserAccount account = users.findById(userId)
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new OauthLoginException("oauth_account_unavailable"));
        if (account.role() != UserRole.USER) {
            throw new OauthLoginException("oauth_admin_link_forbidden");
        }
        return account;
    }

    /** Código público estable; nunca incluye claims ni detalles del proveedor. */
    public static final class OauthLoginException extends RuntimeException {
        private final String publicCode;

        public OauthLoginException(String publicCode) {
            super(publicCode);
            this.publicCode = publicCode;
        }

        public String publicCode() {
            return publicCode;
        }
    }
}

package es.ubu.batchdownloader.identity.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Mantiene el snapshot de propietario alineado sin usarlo para autorizar. */
@Service
public class AccountProfileService {
    private final IdentityService identities;
    private final JdbcTemplate jdbc;

    public AccountProfileService(IdentityService identities, JdbcTemplate jdbc) {
        this.identities = identities;
        this.jdbc = jdbc;
    }

    @Transactional
    public IdentityView changeUsername(UUID userId, String username) {
        IdentityView changed = identities.updateUsername(userId, username);
        jdbc.update(
                "UPDATE bundles SET owner_username = ? WHERE owner_id = ?",
                changed.username(), userId.toString());
        return changed;
    }
}

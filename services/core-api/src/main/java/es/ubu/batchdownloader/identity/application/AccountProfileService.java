package es.ubu.batchdownloader.identity.application;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Gestiona cambios de perfil sobre la identidad UUID canónica. */
@Service
public class AccountProfileService {
    private final IdentityService identities;

    public AccountProfileService(IdentityService identities) {
        this.identities = identities;
    }

    public IdentityView changeUsername(UUID userId, String username) {
        return identities.updateUsername(userId, username);
    }
}

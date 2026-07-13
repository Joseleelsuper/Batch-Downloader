package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.util.UUID;

public record IdentityView(
        UUID id,
        String username,
        String email,
        boolean emailVerified,
        UserRole role,
        boolean notifyOnJobCompletion) {

    public static IdentityView from(UserAccount user) {
        return new IdentityView(
                user.id(), user.username(), user.email(), user.emailVerified(), user.role(),
                user.notifyOnJobCompletion());
    }
}

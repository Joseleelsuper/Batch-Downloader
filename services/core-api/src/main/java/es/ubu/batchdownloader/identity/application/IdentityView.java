package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Representa los datos inmutables de {@code IdentityView}.
 *
 * @param id Valor de {@code id} incluido en el record.
 * @param username Valor de {@code username} incluido en el record.
 * @param email Valor de {@code email} incluido en el record.
 * @param emailVerified Valor de {@code emailVerified} incluido en el record.
 * @param role Valor de {@code role} incluido en el record.
 * @param notifyOnJobCompletion Valor de {@code notifyOnJobCompletion} incluido en el record.
 * @param createdAt Valor de {@code createdAt} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public record IdentityView(
        UUID id,
        String username,
        String email,
        boolean emailVerified,
        UserRole role,
        boolean notifyOnJobCompletion,
        Instant createdAt) {

    /**
     * Ejecuta la operación {@code from}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @return Resultado producido por {@code from}.
     */
    public static IdentityView from(UserAccount user) {
        return new IdentityView(
                user.id(), user.username(), user.email(), user.emailVerified(), user.role(),
                user.notifyOnJobCompletion(), user.createdAt());
    }
}

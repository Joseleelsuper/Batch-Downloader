package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Instant;
import java.util.UUID;

/**
 * Expone identidad, correo y rol de una cuenta sin transportar contraseña ni su hash.
 *
 * @param id UUID estable del agregado que se consulta o reconstruye.
 * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
 * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
 *     normalizada.
 * @param emailVerified Indica que se ha confirmado el control del correo de la cuenta.
 * @param role Rol USER o ADMIN que determina el acceso permitido.
 * @param createdAt Instante de creación original del agregado.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.domain.UserAccount
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public record IdentityView(
        UUID id,
        String username,
        String email,
        boolean emailVerified,
        UserRole role,
        Instant createdAt) {

    /**
     * Proyecta los campos de identidad que pueden viajar en respuestas de cuenta.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @return vista sin credenciales del agregado.
     */
    public static IdentityView from(UserAccount user) {
        return new IdentityView(
                user.id(), user.username(), user.email(), user.emailVerified(), user.role(),
                user.createdAt());
    }
}

package es.ubu.batchdownloader.identity.application.port;

import java.util.UUID;

/**
 * Permite revocar las sesiones de una cuenta tras un cambio de credenciales sin acoplar la
 * aplicación al almacén de sesiones.
 *
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public interface AccountSessionInvalidator {
    /**
     * Revoca las sesiones asociadas al UUID canónico después de confirmar el cambio de contraseña.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     */
    void invalidateAll(UUID userId);
}

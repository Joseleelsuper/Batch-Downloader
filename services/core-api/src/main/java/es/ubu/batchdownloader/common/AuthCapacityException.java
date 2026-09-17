package es.ubu.batchdownloader.common;

/**
 * Señala que un cálculo de contraseña no puede completarse dentro de la capacidad o espera
 * disponibles.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ServiceUnavailableException
 * @see es.ubu.batchdownloader.identity.infrastructure.security.BoundedPasswordEncoder
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class AuthCapacityException extends ServiceUnavailableException {
    /**
     * Fija auth_busy y reintento en un segundo para saturación o interrupción del cálculo de
     * autenticación.
     */
    public AuthCapacityException() {
        super("auth_busy", "El servicio de autenticación está ocupado. Inténtalo de nuevo.", 1);
    }
}

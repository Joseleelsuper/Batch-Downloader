package es.ubu.batchdownloader.common;

/**
 * Indica que la capacidad de cálculo de contraseñas está temporalmente agotada.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class AuthCapacityException extends ServiceUnavailableException {
    /** Crea una respuesta estable para la saturación de BCrypt. */
    public AuthCapacityException() {
        super("auth_busy", "El servicio de autenticación está ocupado. Inténtalo de nuevo.", 1);
    }
}

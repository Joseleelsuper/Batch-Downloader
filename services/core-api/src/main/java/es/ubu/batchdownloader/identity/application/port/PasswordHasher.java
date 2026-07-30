package es.ubu.batchdownloader.identity.application.port;

/**
 * Define el contrato de {@code PasswordHasher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface PasswordHasher {
    /**
     * Indica si existe el recurso mediante {@code hash}.
     *
     * @param rawPassword Valor de {@code rawPassword} utilizado por la operación.
     * @return Resultado producido por {@code hash}.
     */
    String hash(String rawPassword);
    /**
     * Ejecuta la operación {@code matches}.
     *
     * @param rawPassword Valor de {@code rawPassword} utilizado por la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    boolean matches(String rawPassword, String passwordHash);
}

package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Implementa el componente {@code BcryptPasswordHasher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class BcryptPasswordHasher implements PasswordHasher {
    /**
     * Estado {@code encoder} mantenido por {@code BcryptPasswordHasher}.
     */
    private final PasswordEncoder encoder;

    /**
     * Inicializa una instancia de {@code BcryptPasswordHasher}.
     *
     * @param encoder Valor de {@code encoder} utilizado por la operación.
     */
    BcryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * Indica si existe el recurso mediante {@code hash}.
     *
     * @param rawPassword Valor de {@code rawPassword} utilizado por la operación.
     * @return Resultado producido por {@code hash}.
     */
    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * Implementa {@code matches} para {@code BcryptPasswordHasher}.
     *
     * @param rawPassword Valor de {@code rawPassword} utilizado por la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}

package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.application.port.PasswordHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Adapta el codificador de Spring al puerto de hash utilizado por los casos de identidad.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.port.PasswordHasher
 * @see es.ubu.batchdownloader.identity.application.PasswordPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Component
class BcryptPasswordHasher implements PasswordHasher {
    /**
     * Estado {@code encoder} mantenido por {@code BcryptPasswordHasher}.
     */
    private final PasswordEncoder encoder;

    /**
     * Recibe el codificador configurado con coste y límites de capacidad de BCrypt.
     *
     * @param encoder Codificador de contraseñas de Spring configurado para BCrypt y capacidad
     *     acotada.
     */
    BcryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /**
     * {@inheritDoc}
     *
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     */
    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * {@inheritDoc}
     *
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @param passwordHash Hash de contraseña almacenado, nunca la contraseña original.
     */
    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}

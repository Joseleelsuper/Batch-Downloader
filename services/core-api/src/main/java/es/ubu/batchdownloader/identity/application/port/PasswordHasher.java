package es.ubu.batchdownloader.identity.application.port;

/**
 * Separa cálculo y verificación de hashes de contraseña de las reglas del caso de uso y de la
 * biblioteca criptográfica.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.PasswordPolicy
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public interface PasswordHasher {
    /**
     * Calcula un hash verificable de una contraseña que ya cumple las reglas de entrada.
     *
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @return representación codificada del hash para su almacenamiento.
     */
    String hash(String rawPassword);
    /**
     * Compara la contraseña presentada con el hash conservado sin recuperar la original.
     *
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @param passwordHash Hash de contraseña almacenado, nunca la contraseña original.
     * @return true si la contraseña corresponde al hash.
     */
    boolean matches(String rawPassword, String passwordHash);
}

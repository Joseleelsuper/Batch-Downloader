package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;

/**
 * Solicita mensajes de verificación y recuperación que deben confirmarse junto al token
 * correspondiente.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.application.port.IdentityTokenStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public interface IdentityEventPublisher {
    /**
     * Solicita un correo de verificación con el token recién emitido para la cuenta.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     */
    void emailVerificationRequested(UserAccount user, String rawToken);
    /**
     * Solicita un correo de recuperación con el token recién emitido para la cuenta.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     */
    void passwordResetRequested(UserAccount user, String rawToken);
}

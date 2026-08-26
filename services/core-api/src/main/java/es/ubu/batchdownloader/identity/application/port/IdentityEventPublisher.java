package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;

/**
 * Define el contrato de {@code IdentityEventPublisher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface IdentityEventPublisher {
    /**
     * Ejecuta la operación {@code emailVerificationRequested}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     */
    void emailVerificationRequested(UserAccount user, String rawToken);
    /**
     * Ejecuta la operación {@code passwordResetRequested}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     */
    void passwordResetRequested(UserAccount user, String rawToken);
}

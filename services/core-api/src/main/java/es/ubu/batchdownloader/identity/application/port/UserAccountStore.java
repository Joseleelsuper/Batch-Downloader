package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;

/**
 * Define el contrato de {@code UserAccountStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface UserAccountStore {
    /**
     * Ejecuta la operación {@code existsByNormalizedUsername}.
     *
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    boolean existsByNormalizedUsername(String normalizedUsername);
    /**
     * Ejecuta la operación {@code existsByNormalizedEmail}.
     *
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    boolean existsByNormalizedEmail(String normalizedEmail);
    /**
     * Busca el resultado solicitado mediante {@code findById}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @return Resultado producido por {@code findById}.
     */
    Optional<UserAccount> findById(UUID id);
    /**
     * Busca el resultado solicitado mediante {@code findByNormalizedUsername}.
     *
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @return Resultado producido por {@code findByNormalizedUsername}.
     */
    Optional<UserAccount> findByNormalizedUsername(String normalizedUsername);
    /**
     * Busca el resultado solicitado mediante {@code findByNormalizedEmail}.
     *
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @return Resultado producido por {@code findByNormalizedEmail}.
     */
    Optional<UserAccount> findByNormalizedEmail(String normalizedEmail);
    /**
     * Guarda el recurso solicitado mediante {@code save}.
     *
     * @param account Valor de {@code account} utilizado por la operación.
     * @return Resultado producido por {@code save}.
     */
    UserAccount save(UserAccount account);
}

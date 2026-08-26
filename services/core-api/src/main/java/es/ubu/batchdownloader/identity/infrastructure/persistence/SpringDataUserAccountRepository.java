package es.ubu.batchdownloader.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Define el contrato de {@code SpringDataUserAccountRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
interface SpringDataUserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
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
     * Busca el resultado solicitado mediante {@code findByNormalizedUsername}.
     *
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @return Resultado producido por {@code findByNormalizedUsername}.
     */
    Optional<UserAccountEntity> findByNormalizedUsername(String normalizedUsername);
    /**
     * Busca el resultado solicitado mediante {@code findByNormalizedEmail}.
     *
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @return Resultado producido por {@code findByNormalizedEmail}.
     */
    Optional<UserAccountEntity> findByNormalizedEmail(String normalizedEmail);
}

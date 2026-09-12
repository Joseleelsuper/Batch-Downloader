package es.ubu.batchdownloader.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Resuelve ocupación y consulta de nombres y correos normalizados en la tabla de cuentas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.JpaUserAccountStore
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.UserAccountEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
interface SpringDataUserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
    /**
     * Comprueba la ocupación del nombre normalizado sin filtrar el estado de la cuenta.
     *
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @return true si existe una cuenta con esa clave.
     */
    boolean existsByNormalizedUsername(String normalizedUsername);
    /**
     * Comprueba la ocupación del correo normalizado sin filtrar el estado de la cuenta.
     *
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @return true si existe una cuenta con esa clave.
     */
    boolean existsByNormalizedEmail(String normalizedEmail);
    /**
     * Consulta la entidad de cuenta por nombre normalizado sin comprobar autorización.
     *
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @return entidad coincidente o vacío.
     */
    Optional<UserAccountEntity> findByNormalizedUsername(String normalizedUsername);
    /**
     * Consulta la entidad de cuenta por correo normalizado sin comprobar autorización.
     *
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @return entidad coincidente o vacío.
     */
    Optional<UserAccountEntity> findByNormalizedEmail(String normalizedEmail);
}

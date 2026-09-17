package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;

/**
 * Persiste cuentas y consulta sus identidades normalizadas sin decidir permisos, verificación ni
 * estado habilitado.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.domain.UserAccount
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public interface UserAccountStore {
    /**
     * Comprueba si alguna cuenta ocupa la clave normalizada de nombre.
     *
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @return true si la clave está ocupada, aunque la cuenta esté deshabilitada.
     */
    boolean existsByNormalizedUsername(String normalizedUsername);
    /**
     * Comprueba si alguna cuenta ocupa la clave normalizada de correo.
     *
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @return true si la clave está ocupada, aunque la cuenta esté deshabilitada.
     */
    boolean existsByNormalizedEmail(String normalizedEmail);
    /**
     * Consulta la cuenta por su UUID sin filtrar habilitación ni rol.
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     * @return cuenta persistida o vacío.
     */
    Optional<UserAccount> findById(UUID id);
    /**
     * Consulta la cuenta por su nombre normalizado sin filtrar su estado.
     *
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     * @return cuenta coincidente o vacío.
     */
    Optional<UserAccount> findByNormalizedUsername(String normalizedUsername);
    /**
     * Consulta la cuenta por su correo normalizado sin filtrar su estado.
     *
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     * @return cuenta coincidente o vacío.
     */
    Optional<UserAccount> findByNormalizedEmail(String normalizedEmail);
    /**
     * Inserta o actualiza la cuenta respetando las restricciones persistidas de unicidad y
     * concurrencia.
     *
     * @param account Agregado de cuenta que debe consultarse o persistirse.
     * @return cuenta guardada con su versión.
     */
    UserAccount save(UserAccount account);
}

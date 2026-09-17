package es.ubu.batchdownloader.identity.application;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Canaliza cambios de nombre visible conservando la identidad UUID y las mismas reglas de unicidad
 * de las cuentas.
 *
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.domain.UserAccount
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Service
public class AccountProfileService {
    private final IdentityService identities;

    /**
     * Conecta los cambios de perfil con las reglas transaccionales de identidad.
     *
     * @param identities Casos de uso de identidad que conservan el UUID y las restricciones de
     *     unicidad.
     */
    public AccountProfileService(IdentityService identities) {
        this.identities = identities;
    }

    /**
     * Solicita un nuevo nombre visible sin cambiar el propietario de sesiones, bundles ni
     * descargas.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @return vista actualizada de la cuenta.
     */
    public IdentityView changeUsername(UUID userId, String username) {
        return identities.updateUsername(userId, username);
    }
}

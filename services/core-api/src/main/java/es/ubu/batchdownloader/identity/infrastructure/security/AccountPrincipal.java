package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Conserva el UUID canónico como nombre de principal y de sesión, separado del nombre visible
 * mutable y del rol de autorización.
 *
 * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
 * @param displayUsername Nombre visible actual; la identidad persistida de sesión sigue siendo el
 *     UUID.
 * @param role Rol USER o ADMIN que determina el acceso permitido.
 * @see es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public record AccountPrincipal(UUID userId, String displayUsername, UserRole role)
        implements Principal, UserDetails, Serializable {
    @Serial private static final long serialVersionUID = 2L;

    /**
     * Exige UUID, nombre visible no vacío y rol antes de permitir una identidad de sesión.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param displayUsername Nombre visible actual; la identidad persistida de sesión sigue siendo
     *     el UUID.
     * @param role Rol USER o ADMIN que determina el acceso permitido.
     * @throws IllegalArgumentException si falta alguno de los campos obligatorios.
     */
    public AccountPrincipal {
        if (userId == null || displayUsername == null || displayUsername.isBlank() || role == null) {
            throw new IllegalArgumentException("invalid_account_principal");
        }
    }

    /**
     * Proyecta UUID, nombre visible y rol de una cuenta previamente comprobada.
     *
     * @param account Agregado de cuenta que debe consultarse o persistirse.
     * @return principal serializable sin credenciales.
     */
    public static AccountPrincipal from(UserAccount account) {
        return new AccountPrincipal(account.id(), account.username(), account.role());
    }

    /**
     * Expone el UUID como identidad de Principal, estable aunque cambie el nombre visible.
     *
     * @return UUID de la cuenta en texto.
     */
    @Override public String getName() { return userId.toString(); }
    /**
     * Utiliza el UUID como clave de las sesiones indexadas por Spring Security.
     *
     * @return UUID de la cuenta en texto, no su nombre visible.
     */
    @Override public String getUsername() { return userId.toString(); }
    /**
     * Evita conservar un hash o contraseña dentro del principal serializado en la sesión.
     *
     * @return cadena vacía.
     */
    @Override public String getPassword() { return ""; }
    /**
     * Convierte el rol del dominio en la autoridad de Spring que comprueba las rutas.
     *
     * @return colección con ROLE_USER o ROLE_ADMIN.
     */
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}

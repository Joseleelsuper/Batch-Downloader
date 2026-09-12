package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.common.ForbiddenException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.application.PasswordPolicy;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Comprueba credenciales de usuario y administración con hash de relleno para cuentas ausentes y
 * exige las condiciones propias de cada rol.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal
 * @see es.ubu.batchdownloader.identity.application.PasswordPolicy
 * @see es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Component
public class AccountAuthenticator {
    private static final String DUMMY_PASSWORD = "not-a-real-account-password";

    private final UserAccountStore users;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    /**
     * Conecta cuentas y BCrypt y calcula el hash de relleno usado cuando una identidad no existe.
     *
     * @param users Persistencia de cuentas y consultas de unicidad de correo y nombre normalizados.
     * @param passwords Codificador de Spring usado para comparar claves y construir el hash de
     *     relleno.
     */
    AccountAuthenticator(UserAccountStore users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(DUMMY_PASSWORD);
    }

    /**
     * Comprueba correo, contraseña, habilitación y rol USER antes de exigir correo verificado;
     * limita la entrada a 72 bytes UTF-8.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @return autenticación sin credenciales con UUID de la cuenta.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si no coinciden las credenciales
     *     o la cuenta no es un USER habilitado.
     * @throws es.ubu.batchdownloader.common.ForbiddenException solo si las credenciales son
     *     correctas pero el correo no está verificado.
     */
    public Authentication authenticateUser(String email, String rawPassword) {
        PasswordPolicy.requireSupportedForLogin(rawPassword);
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        UserAccount account = users.findByNormalizedEmail(normalizedEmail).orElse(null);
        String encoded = account != null ? account.passwordHash() : dummyHash;
        boolean matches = passwords.matches(rawPassword, encoded);
        if (!matches || account == null || !account.enabled() || account.role() != UserRole.USER) {
            throw invalidCredentials();
        }
        if (!account.emailVerified()) {
            throw new ForbiddenException(
                    "email_not_verified", "Debes verificar tu correo antes de iniciar sesión.");
        }
        return authenticated(account);
    }

    /**
     * Comprueba nombre normalizado, contraseña, habilitación y rol ADMIN usando el mismo hash de
     * relleno para identidades ausentes.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param rawPassword Contraseña sin hash que no debe persistirse ni registrarse.
     * @return autenticación administrativa sin contraseña.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si fallan credenciales,
     *     habilitación o rol.
     */
    public Authentication authenticateAdmin(String username, String rawPassword) {
        PasswordPolicy.requireSupportedForLogin(rawPassword);
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        UserAccount account = users.findByNormalizedUsername(normalized).orElse(null);
        String encoded = account != null ? account.passwordHash() : dummyHash;
        boolean matches = passwords.matches(rawPassword, encoded);
        if (!matches || account == null || !account.enabled() || account.role() != UserRole.ADMIN) {
            throw invalidCredentials();
        }
        return authenticated(account);
    }

    /**
     * Convierte una cuenta previamente autorizada en una autenticación cuyo principal usa el UUID
     * como identidad.
     *
     * @param account Agregado de cuenta que debe consultarse o persistirse.
     * @return token de Spring con rol y sin contraseña.
     */
    public Authentication authenticated(UserAccount account) {
        AccountPrincipal principal = AccountPrincipal.from(account);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    /**
     * Unifica los fallos de identidad, contraseña, habilitación y rol sin revelar cuál se produjo.
     *
     * @return error 401 invalid_credentials.
     */
    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("invalid_credentials", "Credenciales incorrectas.");
    }
}

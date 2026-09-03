package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.application.PasswordPolicy;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Implementa el componente {@code AdminBootstrap}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class AdminBootstrap implements ApplicationRunner {
    /**
     * Estado {@code users} mantenido por {@code AdminBootstrap}.
     */
    private final UserAccountStore users;
    /**
     * Estado {@code clock} mantenido por {@code AdminBootstrap}.
     */
    private final Clock clock;
    /**
     * Estado {@code username} mantenido por {@code AdminBootstrap}.
     */
    private final String username;
    /**
     * Estado {@code email} mantenido por {@code AdminBootstrap}.
     */
    private final String email;
    /**
     * Contraseña de arranque que se codifica antes de persistirla.
     */
    private final String password;
    private final PasswordEncoder passwords;

    /**
     * Inicializa una instancia de {@code AdminBootstrap}.
     *
     * @param users Valor de {@code users} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @param username Valor de {@code username} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param password Contraseña administrativa que se codificará antes de persistirla.
     * @param passwords Codificador de contraseñas configurado por la aplicación.
     */
    AdminBootstrap(
            UserAccountStore users,
            Clock clock,
            @Value("${app.auth.bootstrap-admin-username}") String username,
            @Value("${app.auth.bootstrap-admin-email}") String email,
            @Value("${app.auth.bootstrap-admin-password}") String password,
            PasswordEncoder passwords) {
        this.users = users;
        this.clock = clock;
        this.username = username;
        this.email = email;
        this.password = password;
        this.passwords = passwords;
    }

    /**
     * Implementa {@code run} para {@code AdminBootstrap}.
     *
     * @param arguments Valor de {@code arguments} utilizado por la operación.
     */
    @Override
    public void run(ApplicationArguments arguments) {
        if (username.isBlank() || password.isBlank()) return;
        String normalizedUsername = username.strip().toLowerCase(Locale.ROOT);
        UserAccount existing = users.findByNormalizedUsername(normalizedUsername).orElse(null);
        if (existing != null) {
            if (existing.role() != UserRole.ADMIN) {
                throw new IllegalStateException("bootstrap_admin_username_conflict");
            }
            PasswordPolicy.requireSupportedForLogin(password);
            if (passwords.matches(password, existing.passwordHash())) return;
            PasswordPolicy.requireValid(password);
            String encodedPassword = passwords.encode(password);
            existing.changePassword(encodedPassword, clock.instant());
            users.save(existing);
            return;
        }
        PasswordPolicy.requireValid(password);
        String encodedPassword = passwords.encode(password);
        String cleanEmail = email.strip();
        users.save(UserAccount.bootstrapAdmin(
                username.strip(), normalizedUsername, cleanEmail, cleanEmail.toLowerCase(Locale.ROOT),
                encodedPassword, clock.instant()));
    }
}

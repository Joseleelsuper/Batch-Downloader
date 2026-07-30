package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
     * Estado {@code passwordHash} mantenido por {@code AdminBootstrap}.
     */
    private final String passwordHash;

    /**
     * Inicializa una instancia de {@code AdminBootstrap}.
     *
     * @param users Valor de {@code users} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @param username Valor de {@code username} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param passwordHash Valor de {@code passwordHash} utilizado por la operación.
     */
    AdminBootstrap(
            UserAccountStore users,
            Clock clock,
            @Value("${app.auth.bootstrap-admin-username}") String username,
            @Value("${app.auth.bootstrap-admin-email}") String email,
            @Value("${app.auth.bootstrap-admin-password-hash}") String passwordHash) {
        this.users = users;
        this.clock = clock;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    /**
     * Implementa {@code run} para {@code AdminBootstrap}.
     *
     * @param arguments Valor de {@code arguments} utilizado por la operación.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (username.isBlank() || passwordHash.isBlank()) return;
        String normalizedUsername = username.strip().toLowerCase(Locale.ROOT);
        if (users.existsByNormalizedUsername(normalizedUsername)) return;
        String cleanEmail = email.strip();
        users.save(UserAccount.bootstrapAdmin(
                username.strip(), normalizedUsername, cleanEmail, cleanEmail.toLowerCase(Locale.ROOT),
                passwordHash, clock.instant()));
    }
}

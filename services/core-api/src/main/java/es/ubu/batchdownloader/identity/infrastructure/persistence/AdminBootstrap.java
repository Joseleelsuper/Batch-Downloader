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
 * Crea o sincroniza la cuenta administrativa configurada al arrancar, evitando sustituir por
 * administrador una cuenta USER que ocupe su nombre.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.domain.UserAccount
 * @see es.ubu.batchdownloader.identity.application.PasswordPolicy
 * @see es.ubu.batchdownloader.identity.application.port.UserAccountStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
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
     * Recibe identidad administrativa opcional, persistencia, reloj y codificador configurado.
     *
     * @param users Persistencia de cuentas y consultas de unicidad de correo y nombre normalizados.
     * @param clock Reloj usado para creación, caducidad y consumo de tokens y cambios de cuenta.
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @param passwords Codificador de Spring para comparar o renovar el hash administrativo.
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
     * No actúa con nombre o contraseña vacíos; conserva un administrador cuya clave ya coincide y
     * valida las reglas vigentes antes de crear o cambiar su hash.
     *
     * @param arguments Argumentos de arranque de Spring; la inicialización utiliza la configuración
     *     inyectada.
     * @throws IllegalStateException si el nombre configurado pertenece a una cuenta no
     *     administrativa.
     * @throws es.ubu.batchdownloader.common.BadRequestException si una contraseña nueva incumple la
     *     política.
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

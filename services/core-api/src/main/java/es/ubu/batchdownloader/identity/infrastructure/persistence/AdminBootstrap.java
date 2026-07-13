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

@Component
class AdminBootstrap implements ApplicationRunner {
    private final UserAccountStore users;
    private final Clock clock;
    private final String username;
    private final String email;
    private final String passwordHash;

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

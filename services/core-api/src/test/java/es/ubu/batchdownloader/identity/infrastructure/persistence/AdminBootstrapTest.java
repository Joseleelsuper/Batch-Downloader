package es.ubu.batchdownloader.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminBootstrapTest {
    private static final String ADMIN_PASSWORD = "Bootstrap-admin-2026!";
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private final UserAccountStore users = mock(UserAccountStore.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ApplicationArguments arguments = mock(ApplicationArguments.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);

    @Test
    void createsTheConfiguredAdministratorWhenItDoesNotExist() {
        when(users.findByNormalizedUsername("admin")).thenReturn(Optional.empty());
        when(passwords.encode(ADMIN_PASSWORD)).thenReturn("configured-hash");
        AdminBootstrap bootstrap = bootstrap();

        bootstrap.run(arguments);

        var account = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(account.capture());
        assertThat(account.getValue().username()).isEqualTo("admin");
        assertThat(account.getValue().role()).isEqualTo(UserRole.ADMIN);
        assertThat(account.getValue().passwordHash()).isEqualTo("configured-hash");
    }

    @Test
    void updatesThePasswordOfAnExistingAdministrator() {
        UserAccount existing = UserAccount.bootstrapAdmin(
                "admin", "admin", "admin@example.com", "admin@example.com", "old-hash", NOW.minusSeconds(60));
        when(users.findByNormalizedUsername("admin")).thenReturn(Optional.of(existing));
        when(passwords.matches(ADMIN_PASSWORD, "old-hash")).thenReturn(false);
        when(passwords.encode(ADMIN_PASSWORD)).thenReturn("configured-hash");
        AdminBootstrap bootstrap = bootstrap();

        bootstrap.run(arguments);

        assertThat(existing.passwordHash()).isEqualTo("configured-hash");
        assertThat(existing.updatedAt()).isEqualTo(NOW);
        verify(users).save(existing);
    }

    @Test
    void leavesAnExistingAdministratorUntouchedWhenTheHashAlreadyMatches() {
        UserAccount existing = UserAccount.bootstrapAdmin(
                "admin", "admin", "admin@example.com", "admin@example.com",
                "configured-hash", NOW.minusSeconds(60));
        when(users.findByNormalizedUsername("admin")).thenReturn(Optional.of(existing));
        when(passwords.matches(ADMIN_PASSWORD, "configured-hash")).thenReturn(true);

        bootstrap().run(arguments);

        verify(users, never()).save(existing);
        verify(passwords, never()).encode(ADMIN_PASSWORD);
    }

    @Test
    void keepsAnExistingAdministratorWithALegacyPasswordWhenTheHashMatches() {
        String legacyPassword = "legacy-admin-2026";
        UserAccount existing = UserAccount.bootstrapAdmin(
                "admin", "admin", "admin@example.com", "admin@example.com",
                "configured-hash", NOW.minusSeconds(60));
        when(users.findByNormalizedUsername("admin")).thenReturn(Optional.of(existing));
        when(passwords.matches(legacyPassword, "configured-hash")).thenReturn(true);
        AdminBootstrap bootstrap = new AdminBootstrap(
                users, clock, "admin", "admin@batch-downloader.local", legacyPassword, passwords);

        bootstrap.run(arguments);

        verify(users, never()).save(existing);
        verify(passwords, never()).encode(legacyPassword);
    }

    @Test
    void neverPromotesAUserThatCollidesWithTheConfiguredAdminName() {
        UserAccount user = UserAccount.register(
                "admin", "admin", "user@example.com", "user@example.com", "user-hash", NOW);
        when(users.findByNormalizedUsername("admin")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> bootstrap().run(arguments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bootstrap_admin_username_conflict");
        verify(users, never()).save(user);
        verify(passwords, never()).encode(ADMIN_PASSWORD);
    }

    @Test
    void rejectsAnInsecureConfiguredAdministratorPassword() {
        AdminBootstrap bootstrap = new AdminBootstrap(
                users, clock, "admin", "admin@batch-downloader.local", "admin", passwords);

        assertThatThrownBy(() -> bootstrap.run(arguments))
                .isInstanceOfSatisfying(es.ubu.batchdownloader.common.BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_too_short"));
    }

    private AdminBootstrap bootstrap() {
        return new AdminBootstrap(
                users, clock, " admin ", "admin@batch-downloader.local", ADMIN_PASSWORD, passwords);
    }
}

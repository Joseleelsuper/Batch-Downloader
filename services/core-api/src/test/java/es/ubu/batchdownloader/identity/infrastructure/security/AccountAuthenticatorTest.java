package es.ubu.batchdownloader.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.ForbiddenException;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountAuthenticatorTest {
    private UserAccountStore users;
    private PasswordEncoder passwords;
    private AccountAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserAccountStore.class);
        passwords = Mockito.mock(PasswordEncoder.class);
        when(passwords.encode("not-a-real-account-password")).thenReturn("dummy-hash");
        authenticator = new AccountAuthenticator(users, passwords);
    }

    @Test
    void authenticatesAUserByNormalizedEmailAndBuildsAUuidPrincipal() {
        UserAccount user = account(UserRole.USER, "hash", true, true);
        when(users.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("correct-password", "hash")).thenReturn(true);

        var result = authenticator.authenticateUser(" Person@Example.COM ", "correct-password");

        assertThat(result.getPrincipal()).isInstanceOf(AccountPrincipal.class);
        assertThat(result.getName()).isEqualTo(user.id().toString());
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void revealsVerificationStateOnlyAfterThePasswordMatches() {
        UserAccount user = account(UserRole.USER, "hash", false, true);
        when(users.findByNormalizedEmail("person@example.com")).thenReturn(Optional.of(user));
        when(passwords.matches("wrong-password", "hash")).thenReturn(false);
        when(passwords.matches("correct-password", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authenticator.authenticateUser(
                "person@example.com", "wrong-password"))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        exception -> assertThat(exception.code()).isEqualTo("invalid_credentials"));
        assertThatThrownBy(() -> authenticator.authenticateUser(
                "person@example.com", "correct-password"))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        exception -> assertThat(exception.code()).isEqualTo("email_not_verified"));
    }

    @Test
    void usesTheDummyHashForMissingAccounts() {
        when(users.findByNormalizedEmail("missing@example.com")).thenReturn(Optional.empty());
        when(passwords.matches("password", "dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> authenticator.authenticateUser(
                "missing@example.com", "password"))
                .isInstanceOf(UnauthorizedException.class);
        verify(passwords).matches("password", "dummy-hash");

    }

    @Test
    void keepsUserAndAdminCredentialFlowsSeparated() {
        UserAccount user = account(UserRole.USER, "user-hash", true, true);
        UserAccount admin = account(UserRole.ADMIN, "admin-hash", true, true);
        when(users.findByNormalizedUsername("person")).thenReturn(Optional.of(user));
        when(users.findByNormalizedEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(passwords.matches("password", "user-hash")).thenReturn(true);
        when(passwords.matches("password", "admin-hash")).thenReturn(true);

        assertThatThrownBy(() -> authenticator.authenticateAdmin("person", "password"))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> authenticator.authenticateUser("admin@example.com", "password"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void rejectsPasswordsThatExceedBcryptsByteLimitBeforeCheckingTheHash() {
        assertThatThrownBy(() -> authenticator.authenticateUser("person@example.com", "á".repeat(37)))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(exception.code()).isEqualTo("password_too_long"));
    }

    private static UserAccount account(
            UserRole role, String passwordHash, boolean verified, boolean enabled) {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        return UserAccount.rehydrate(
                UUID.randomUUID(), role == UserRole.ADMIN ? "admin" : "person",
                role == UserRole.ADMIN ? "admin" : "person",
                role == UserRole.ADMIN ? "admin@example.com" : "person@example.com",
                role == UserRole.ADMIN ? "admin@example.com" : "person@example.com",
                passwordHash, verified, role, true, enabled, now, now, 0);
    }
}

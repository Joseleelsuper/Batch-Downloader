package es.ubu.batchdownloader.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.time.Instant;
import java.util.Optional;
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
    void authenticatesAnAdminByNormalizedUsername() {
        UserAccount admin = UserAccount.bootstrapAdmin(
                "admin", "admin", "admin@example.com", "admin@example.com", "hash",
                Instant.parse("2026-08-08T00:00:00Z"));
        when(users.findByNormalizedUsername("admin")).thenReturn(Optional.of(admin));
        when(passwords.matches("correct-password", "hash")).thenReturn(true);

        var result = authenticator.authenticateAdmin(" Admin ", "correct-password");

        assertThat(result.getPrincipal()).isInstanceOf(AccountPrincipal.class);
        assertThat(result.getName()).isEqualTo(admin.id().toString());
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
    }

    @Test
    void usesTheDummyHashForMissingAdminAccounts() {
        when(users.findByNormalizedUsername("missing")).thenReturn(Optional.empty());
        when(passwords.matches("password", "dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> authenticator.authenticateAdmin("missing", "password"))
                .isInstanceOf(UnauthorizedException.class);
    }
}

package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DownloadRequestOwnerTest {
    @Test
    void hashesBrowserAndNetworkValuesWithoutPersistingTheirRawForm() {
        DownloadRequestOwner resolver = new DownloadRequestOwner(
                Mockito.mock(UserAccountStore.class), "test-owner-secret");

        var owner = resolver.resolve(null, "plain-browser-token", "203.0.113.9");

        assertThat(owner.authenticated()).isFalse();
        assertThat(owner.anonymousOwnerHash()).hasSize(64).isNotEqualTo("plain-browser-token");
        assertThat(owner.anonymousIpHash()).hasSize(64).isNotEqualTo("203.0.113.9");
        assertThat(owner.canAccess(null, owner.anonymousOwnerHash())).isTrue();
    }

    @Test
    void signedInOwnerKeepsBrowserAccessToJobsCreatedBeforeLogin() {
        UserAccountStore users = Mockito.mock(UserAccountStore.class);
        UserAccount account = UserAccount.register(
                "Alice", "alice", "alice@example.test", "alice@example.test", "hash", Instant.EPOCH);
        when(users.findByNormalizedUsername("alice")).thenReturn(Optional.of(account));
        DownloadRequestOwner resolver = new DownloadRequestOwner(users, "test-owner-secret");

        var anonymous = resolver.resolve(null, "same-browser", "203.0.113.9");
        var signedIn = resolver.resolve(" ALICE ", "same-browser", "203.0.113.9");

        assertThat(signedIn.authenticated()).isTrue();
        assertThat(signedIn.userId()).isEqualTo(account.id());
        assertThat(signedIn.canAccess(null, anonymous.anonymousOwnerHash())).isTrue();
        assertThat(signedIn.canAccess(account.id(), null)).isTrue();
        assertThat(signedIn.canAccess(UUID.randomUUID(), null)).isFalse();
    }
}

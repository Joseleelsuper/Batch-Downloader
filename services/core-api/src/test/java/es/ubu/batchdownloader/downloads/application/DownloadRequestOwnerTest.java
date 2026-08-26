package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code DownloadRequestOwnerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class DownloadRequestOwnerTest {
    /**
     * Comprueba el escenario {@code hashesBrowserAndNetworkValuesWithoutPersistingTheirRawForm}.
     */
    @Test
    void hashesBrowserAndNetworkValuesWithoutPersistingTheirRawForm() {
        DownloadRequestOwner resolver = new DownloadRequestOwner("test-owner-secret");

        var owner = resolver.resolve((UUID) null, "plain-browser-token", "203.0.113.9");

        assertThat(owner.authenticated()).isFalse();
        assertThat(owner.anonymousOwnerHash()).hasSize(64).isNotEqualTo("plain-browser-token");
        assertThat(owner.anonymousIpHash()).hasSize(64).isNotEqualTo("203.0.113.9");
        assertThat(owner.canAccess(null, owner.anonymousOwnerHash())).isTrue();
    }

    /**
     * Comprueba el escenario {@code signedInOwnerKeepsBrowserAccessToJobsCreatedBeforeLogin}.
     */
    @Test
    void signedInOwnerKeepsBrowserAccessToJobsCreatedBeforeLogin() {
        UUID userId = UUID.randomUUID();
        DownloadRequestOwner resolver = new DownloadRequestOwner("test-owner-secret");

        var anonymous = resolver.resolve((UUID) null, "same-browser", "203.0.113.9");
        var signedIn = resolver.resolve(userId, "same-browser", "203.0.113.9");

        assertThat(signedIn.authenticated()).isTrue();
        assertThat(signedIn.userId()).isEqualTo(userId);
        assertThat(signedIn.canAccess(null, anonymous.anonymousOwnerHash())).isTrue();
        assertThat(signedIn.canAccess(userId, null)).isTrue();
        assertThat(signedIn.canAccess(UUID.randomUUID(), null)).isFalse();
    }
}

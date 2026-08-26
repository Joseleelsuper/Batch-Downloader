package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AccountProfileServiceTest {
    @Test
    void updatesTheCanonicalIdentityWithoutUsernameSnapshots() {
        IdentityService identities = Mockito.mock(IdentityService.class);
        UUID userId = UUID.randomUUID();
        IdentityView changed = new IdentityView(
                userId, "new-name", "person@example.com", true, UserRole.USER, true,
                Instant.EPOCH, List.of("LOCAL"));
        when(identities.updateUsername(userId, "new-name")).thenReturn(changed);
        AccountProfileService profiles = new AccountProfileService(identities);

        assertThat(profiles.changeUsername(userId, "new-name")).isSameAs(changed);

        verify(identities).updateUsername(userId, "new-name");
    }
}

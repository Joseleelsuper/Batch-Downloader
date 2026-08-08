package es.ubu.batchdownloader.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

class AccountProfileServiceTest {
    @Test
    void updatesTheLegacyOwnerSnapshotByStableUuid() {
        IdentityService identities = Mockito.mock(IdentityService.class);
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        UUID userId = UUID.randomUUID();
        IdentityView changed = new IdentityView(
                userId, "new-name", "person@example.com", true, UserRole.USER, true,
                Instant.EPOCH, List.of("LOCAL"));
        when(identities.updateUsername(userId, "new-name")).thenReturn(changed);
        AccountProfileService profiles = new AccountProfileService(identities, jdbc);

        assertThat(profiles.changeUsername(userId, "new-name")).isSameAs(changed);

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("WHERE owner_id = ?"),
                parameters.capture());
        assertThat(parameters.getValue()).containsExactly("new-name", userId.toString());
    }
}

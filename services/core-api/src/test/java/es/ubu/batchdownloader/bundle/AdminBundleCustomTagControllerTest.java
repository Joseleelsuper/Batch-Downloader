package es.ubu.batchdownloader.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.bundle.BundleDtos.CreateCustomTagRequest;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AdminBundleCustomTagControllerTest {
    @Test
    void listsSharedTagsAndAttributesCreationToTheAuthenticatedAdministrator() {
        UserBundleRepository bundles = mock(UserBundleRepository.class);
        UUID administratorId = UUID.randomUUID();
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(
                new AccountPrincipal(administratorId, "admin", UserRole.ADMIN));
        when(bundles.customTags()).thenReturn(List.of("Existing"));
        when(bundles.createCustomTag(administratorId, "New Tag")).thenReturn("New Tag");
        AdminBundleCustomTagController controller = new AdminBundleCustomTagController(bundles);

        assertThat(controller.customTags()).containsExactly("Existing");
        assertThat(controller.createCustomTag(new CreateCustomTagRequest("New Tag"), authentication)
                .tag()).isEqualTo("New Tag");
        verify(bundles).createCustomTag(administratorId, "New Tag");
    }

    @Test
    void rejectsMissingOrUnrecognizedPrincipal() {
        UserBundleRepository bundles = mock(UserBundleRepository.class);
        AdminBundleCustomTagController controller = new AdminBundleCustomTagController(bundles);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn("invalid");

        assertThatThrownBy(() -> controller.createCustomTag(
                new CreateCustomTagRequest("New Tag"), null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.createCustomTag(
                new CreateCustomTagRequest("New Tag"), authentication))
                .isInstanceOf(UnauthorizedException.class);
    }
}

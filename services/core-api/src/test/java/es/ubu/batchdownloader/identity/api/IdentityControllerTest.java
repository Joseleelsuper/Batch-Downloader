package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountAuthenticator;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Agrupa los escenarios de prueba de {@code IdentityControllerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class IdentityControllerTest {
    /**
     * Dato compartido {@code identities} para los escenarios de prueba.
     */
    private final IdentityService identities = mock(IdentityService.class);
    private final CurrentAccount currentAccount = mock(CurrentAccount.class);
    /**
     * Dato compartido {@code controller} para los escenarios de prueba.
     */
    private final IdentityController controller = new IdentityController(
            identities,
            mock(AccountAuthenticator.class),
            currentAccount,
            mock(SecurityContextRepository.class),
            mock(SessionAuthenticationStrategy.class),
            new AuthRateLimiter(100, 100, 100));

    /**
     * Comprueba el escenario {@code anonymousCurrentIdentityReturnsNoContent}.
     */
    @Test
    void anonymousCurrentIdentityReturnsNoContent() {
        assertThat(controller.me(null).getStatusCode().value()).isEqualTo(204);
    }

    /**
     * Comprueba el escenario {@code authenticatedCurrentIdentityReturnsTheAccount}.
     */
    @Test
    void authenticatedCurrentIdentityReturnsTheAccount() {
        Authentication authentication = mock(Authentication.class);
        UserAccount account = mock(UserAccount.class);
        UUID accountId = UUID.randomUUID();
        IdentityView identity = new IdentityView(
                accountId, "admin", "admin@example.test", true, UserRole.ADMIN, true,
                Instant.EPOCH, List.of("LOCAL"));
        when(authentication.isAuthenticated()).thenReturn(true);
        when(currentAccount.require(authentication)).thenReturn(account);
        when(account.id()).thenReturn(accountId);
        when(identities.findById(accountId)).thenReturn(identity);

        var response = controller.me(authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(identity);
        verify(identities).findById(accountId);
    }
}

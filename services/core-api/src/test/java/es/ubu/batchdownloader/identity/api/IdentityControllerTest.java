package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
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
    /**
     * Dato compartido {@code controller} para los escenarios de prueba.
     */
    private final IdentityController controller = new IdentityController(
            identities,
            mock(AuthenticationManager.class),
            mock(SecurityContextRepository.class),
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
        Principal principal = () -> "admin";
        IdentityView identity = new IdentityView(
                UUID.randomUUID(), "admin", "admin@example.test", true, UserRole.ADMIN, true);
        when(identities.findByUsername("admin")).thenReturn(identity);

        var response = controller.me(principal);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(identity);
        verify(identities).findByUsername("admin");
    }
}

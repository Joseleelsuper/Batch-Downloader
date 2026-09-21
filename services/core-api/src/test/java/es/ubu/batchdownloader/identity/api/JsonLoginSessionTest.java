package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

class JsonLoginSessionTest {
    @Test
    void requestsMagicLinksWithUniformAcceptedResponse() {
        IdentityService identities = mock(IdentityService.class);
        IdentityController controller = new IdentityController(
                identities, mock(AccountAuthenticator.class), mock(CurrentAccount.class),
                mock(SecurityContextRepository.class), mock(SessionAuthenticationStrategy.class),
                new AuthRateLimiter(100, 100, 100));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThat(controller.requestMagicLink(
                new IdentityController.MagicLinkRequest("person@example.com"), request).getStatusCode().value())
                .isEqualTo(202);
        verify(identities).requestMagicLink("person@example.com");
    }

    @Test
    void consumesMagicLinkRotatesTheSessionAndPersistsTheSecurityContext() {
        IdentityService identities = mock(IdentityService.class);
        AccountAuthenticator authenticator = mock(AccountAuthenticator.class);
        SecurityContextRepository contexts = mock(SecurityContextRepository.class);
        SessionAuthenticationStrategy sessions = mock(SessionAuthenticationStrategy.class);
        Authentication authentication = mock(Authentication.class);
        UserAccount user = mock(UserAccount.class);
        UUID userId = UUID.randomUUID();
        IdentityView view = new IdentityView(
                userId, "person", "person@example.com", true, UserRole.USER, Instant.EPOCH);
        when(identities.consumeMagicLink("magic-token")).thenReturn(user);
        when(authenticator.authenticated(user)).thenReturn(authentication);
        when(identities.view(user)).thenReturn(view);
        IdentityController controller = new IdentityController(
                identities, authenticator, mock(CurrentAccount.class), contexts, sessions,
                new AuthRateLimiter(100, 100, 100));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.confirmMagicLink(
                new IdentityController.TokenRequest("magic-token"), request, response))
                .isSameAs(view);

        verify(sessions).onAuthentication(authentication, request, response);
        ArgumentCaptor<org.springframework.security.core.context.SecurityContext> context =
                ArgumentCaptor.forClass(org.springframework.security.core.context.SecurityContext.class);
        verify(contexts).saveContext(context.capture(), any(), any());
        assertThat(context.getValue().getAuthentication()).isSameAs(authentication);
    }
}

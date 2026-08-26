package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;

class JsonLoginSessionTest {
    @Test
    void rotatesTheSessionAndPersistsTheSecurityContextAfterUserLogin() {
        IdentityService identities = Mockito.mock(IdentityService.class);
        AccountAuthenticator authenticator = Mockito.mock(AccountAuthenticator.class);
        CurrentAccount currentAccount = Mockito.mock(CurrentAccount.class);
        SecurityContextRepository contexts = Mockito.mock(SecurityContextRepository.class);
        SessionAuthenticationStrategy sessions = Mockito.mock(SessionAuthenticationStrategy.class);
        Authentication authentication = Mockito.mock(Authentication.class);
        UserAccount user = Mockito.mock(UserAccount.class);
        UUID userId = UUID.randomUUID();
        IdentityView view = new IdentityView(
                userId, "person", "person@example.com", true, UserRole.USER, true,
                Instant.EPOCH, List.of("LOCAL"));
        when(authenticator.authenticateUser("person@example.com", "correct-password"))
                .thenReturn(authentication);
        when(currentAccount.require(authentication)).thenReturn(user);
        when(user.id()).thenReturn(userId);
        when(identities.findById(userId)).thenReturn(view);
        IdentityController controller = new IdentityController(
                identities, authenticator, currentAccount, contexts, sessions,
                new AuthRateLimiter(100, 100, 100));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(controller.login(
                new IdentityController.LoginRequest("person@example.com", "correct-password"),
                request, response)).isSameAs(view);

        verify(sessions).onAuthentication(authentication, request, response);
        ArgumentCaptor<SecurityContext> context = ArgumentCaptor.forClass(SecurityContext.class);
        verify(contexts).saveContext(context.capture(), any(), any());
        assertThat(context.getValue().getAuthentication()).isSameAs(authentication);
    }
}

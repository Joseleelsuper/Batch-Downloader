package es.ubu.batchdownloader.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.identity.api.OAuthLoginController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;

class GoogleOAuthFailureHandlerTest {
    @Test
    void clearsTheSavedDestinationAndRedirectsToThePublicErrorPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(OAuthLoginController.RETURN_TO_SESSION_ATTRIBUTE, "/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new GoogleOAuthFailureHandler().onAuthenticationFailure(
                request,
                response,
                new AuthenticationServiceException("provider detail"));

        assertThat(request.getSession().getAttribute(OAuthLoginController.RETURN_TO_SESSION_ATTRIBUTE)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/error?code=oauth_failed&status=401");
    }
}

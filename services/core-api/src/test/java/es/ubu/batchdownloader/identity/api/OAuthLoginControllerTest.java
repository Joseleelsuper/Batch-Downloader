package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OAuthLoginControllerTest {
    @Test
    void reportsOnlyGoogleAsUnavailableWhenCredentialsAreMissing() throws IOException {
        OAuthLoginController controller = new OAuthLoginController(registrationId -> null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.google("/dashboard", request, response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getErrorMessage()).isEqualTo("google_oauth_not_configured");
        assertThat(request.getSession(false)).isNull();
    }

    @ParameterizedTest
    @CsvSource(value = {
        "/dashboard/bundles?sort=recent|/dashboard/bundles?sort=recent",
        "<null>|/dashboard",
        "https://evil.example|/dashboard",
        "//evil.example/path|/dashboard",
        "/\\evil.example|/dashboard",
        "dashboard|/dashboard",
        "/dashboard%0ASet-Cookie:test|/dashboard",
        "/%2Fevil.example|/dashboard",
        "/%255cevil.example|/dashboard"
    }, delimiter = '|', nullValues = "<null>")
    void acceptsOnlyRelativeInternalDestinations(String input, String expected) {
        assertThat(OAuthLoginController.safeReturnTo(input)).isEqualTo(expected);
    }
}

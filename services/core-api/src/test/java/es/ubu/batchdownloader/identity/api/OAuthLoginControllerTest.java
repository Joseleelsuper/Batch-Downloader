package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OAuthLoginControllerTest {
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

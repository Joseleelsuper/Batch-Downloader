package es.ubu.batchdownloader.softwarerequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import es.ubu.batchdownloader.common.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SoftwareRequestInputTest {
    @Test void normalizesNamesHostDefaultPortAndFragmentsWithoutBreakingEscapedPaths() {
        var first = SoftwareRequestInput.parse("  New   Editor  ", "https://EXAMPLE.org:443/download%20page#intro");
        var second = SoftwareRequestInput.parse("NEW EDITOR", "https://example.org/download%20page");
        assertThat(first.name()).isEqualTo("New Editor");
        assertThat(first.normalizedName()).isEqualTo(second.normalizedName());
        assertThat(first.officialUrl()).isEqualTo("https://example.org/download%20page");
        assertThat(first.urlKey()).isEqualTo(second.urlKey());
    }

    @ParameterizedTest @ValueSource(strings = {
        "http://example.org", "javascript:alert(1)", "https://user:secret@example.org",
        "https://example.org:8443/", "https://127.0.0.1/", "https://10.0.0.1/",
        "https://[::1]/", "https://[2001:db8::1]/", "https://2130706433/",
        "https://localhost/", "https://host.local/", "https://host.internal./",
        "https://host.test/", "https://example.org\\@127.0.0.1/", "file:///etc/passwd", ""
    })
    void rejectsUnsafeOrMalformedUrlWithoutAnyNetworkDependency(String url) {
        assertThatThrownBy(() -> SoftwareRequestInput.parse("Editor", url))
                .isInstanceOfSatisfying(BadRequestException.class,
                        error -> assertThat(error.code()).isEqualTo("invalid_software_request_url"));
    }

    @Test void rejectsMissingOversizedAndControlCharacterNames() {
        for (String name : new String[] {null, "  ", "x".repeat(161), "Bad\u0000name"}) {
            assertThatThrownBy(() -> SoftwareRequestInput.parse(name, "https://example.org"))
                    .isInstanceOf(BadRequestException.class);
        }
    }
}

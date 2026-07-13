package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicHttpsUriPolicyTest {
    @Test
    void acceptsOnlyHttpsWithEntirelyPublicDnsAnswers() {
        PublicHttpsUriPolicy publicPolicy = new PublicHttpsUriPolicy(
                host -> List.of(address("8.8.8.8")));

        assertThatCode(() -> publicPolicy.validate(URI.create("https://downloads.example.com/app.exe")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> publicPolicy.validate(URI.create("http://downloads.example.com/app.exe")))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("https_required");
    }

    @Test
    void rejectsAHostWhenAnyDnsAnswerIsPrivate() {
        PublicHttpsUriPolicy policy = new PublicHttpsUriPolicy(host -> List.of(
                address("8.8.8.8"),
                address("127.0.0.1")));

        assertThatThrownBy(() -> policy.validate(URI.create("https://downloads.example.com/app.exe")))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("non_public_download_host");
    }

    @Test
    void classifiesReservedRangesAsNonPublic() throws Exception {
        assertThatCode(() -> {
            if (PublicHttpsUriPolicy.isPublic(InetAddress.getByName("192.0.2.1"))) {
                throw new AssertionError("documentation address was accepted");
            }
            if (PublicHttpsUriPolicy.isPublic(InetAddress.getByName("100.64.0.1"))) {
                throw new AssertionError("carrier-grade NAT address was accepted");
            }
        }).doesNotThrowAnyException();
    }

    private InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}

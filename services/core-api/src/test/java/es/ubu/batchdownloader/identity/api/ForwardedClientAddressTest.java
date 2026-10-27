package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.RateLimitException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;

class ForwardedClientAddressTest {
    @Test
    void ignoresForgedPrefixesAndForwardedHeadersWithoutLosingTheClientOrHttps() throws Exception {
        AuthRateLimiter limiter = new AuthRateLimiter(1, 3, 20);
        String first = address("172.20.0.2", "192.0.2.10, 198.51.100.40, 172.20.0.3", true);
        String second = address("172.20.0.2", "192.0.2.99, 198.51.100.40, 172.20.0.3", true);
        assertThat(first).isEqualTo("198.51.100.40");
        limiter.magicLinkConfirmation(first);
        assertThatThrownBy(() -> limiter.magicLinkConfirmation(second)).isInstanceOf(RateLimitException.class);
        assertThat(address("198.51.100.40", "192.0.2.10", false)).isEqualTo("198.51.100.40");
        assertThat(address("172.20.0.2", "192.0.2.10, 2001:4860:4860::8888", true))
                .isEqualTo("2001:4860:4860::8888");
    }

    private String address(String peer, String chain, boolean secure) throws IOException, ServletException {
        Request request = new Request(new Connector());
        request.setCoyoteRequest(new org.apache.coyote.Request());
        request.setRemoteAddr(peer);
        request.setRemoteHost(peer);
        request.getCoyoteRequest().scheme().setString("http");
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-For").setString(chain);
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Forwarded-Proto").setString("https");
        request.getCoyoteRequest().getMimeHeaders().addValue("Forwarded").setString("for=192.0.2.77");
        request.getCoyoteRequest().getMimeHeaders().addValue("X-Real-IP").setString("192.0.2.88");
        AtomicReference<String> result = new AtomicReference<>();
        RemoteIpValve valve = new RemoteIpValve();
        valve.setRemoteIpHeader("x-forwarded-for");
        valve.setProtocolHeader("x-forwarded-proto");
        valve.setNext(new ValveBase() {
            @Override
            public void invoke(Request current, Response response) {
                result.set(current.getRemoteAddr());
                assertThat(current.isSecure()).isEqualTo(secure);
            }
        });
        valve.invoke(request, new Response());
        return result.get();
    }
}

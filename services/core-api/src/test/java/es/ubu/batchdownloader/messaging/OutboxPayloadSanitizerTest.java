package es.ubu.batchdownloader.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OutboxPayloadSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OutboxPayloadSanitizer sanitizer = new OutboxPayloadSanitizer(mapper);

    @Test
    void purgesAuthenticationDeliveryCiphertextAfterPublication() throws Exception {
        String payload = """
                {"payload":{"template":"MAGIC_LINK","recipient":"person@example.com",
                "parameters":{"username":"person","token":"enc:v1:sensitive"}}}
                """;

        var sanitized = mapper.readTree(sanitizer.afterPublish(
                "notification.email.requested", payload));

        assertThat(sanitized.at("/payload/parameters/token").isMissingNode()).isTrue();
        assertThat(sanitized.at("/payload/parameters/deliveryTokenPurged").asBoolean()).isTrue();
        assertThat(sanitized.toString()).doesNotContain("enc:v1:sensitive");
    }

}

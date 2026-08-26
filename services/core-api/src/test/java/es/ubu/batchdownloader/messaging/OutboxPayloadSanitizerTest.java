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
                {"payload":{"template":"EMAIL_VERIFICATION","recipient":"person@example.com",
                "parameters":{"username":"person","token":"enc:v1:sensitive"}}}
                """;

        var sanitized = mapper.readTree(sanitizer.afterPublish(
                "notification.email.requested", payload));

        assertThat(sanitized.at("/payload/parameters/token").isMissingNode()).isTrue();
        assertThat(sanitized.at("/payload/parameters/deliveryTokenPurged").asBoolean()).isTrue();
        assertThat(sanitized.toString()).doesNotContain("enc:v1:sensitive");
    }

    @Test
    void leavesDownloadAndUnrelatedEventsUntouched() {
        String download = "{\"payload\":{\"template\":\"DOWNLOAD_READY\",\"parameters\":{\"token\":\"legacy\"}}}";
        assertThat(sanitizer.afterPublish("notification.email.requested", download)).isEqualTo(download);
        assertThat(sanitizer.afterPublish("download.job.requested", download)).isEqualTo(download);
    }
}

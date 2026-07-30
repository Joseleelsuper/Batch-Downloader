package es.ubu.batchdownloader.downloads.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code MinioZipUriSignerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class MinioZipUriSignerTest {

    /**
     * Comprueba el escenario {@code signsUsingTheBrowserReachableEndpoint}.
     */
    @Test
    void signsUsingTheBrowserReachableEndpoint() {
        MinioZipUriSigner signer = new MinioZipUriSigner(
                "http://localhost:19000", "access-key", "secret-key", "zips", "us-east-1");

        URI signed = signer.signGet("jobs/job-id/batch.zip", Duration.ofMinutes(5));

        assertThat(signed.getScheme()).isEqualTo("http");
        assertThat(signed.getHost()).isEqualTo("localhost");
        assertThat(signed.getPort()).isEqualTo(19000);
        assertThat(signed.getPath()).isEqualTo("/zips/jobs/job-id/batch.zip");
        assertThat(signed.getQuery()).contains("X-Amz-Signature=");
        assertThat(signed.getQuery()).contains("response-content-disposition=");
    }
}

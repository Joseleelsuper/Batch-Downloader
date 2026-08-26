package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.bundle.BundleRepository;
import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Verifica la preparación aditiva del enlace usado por el autointento. */
class DownloadJobControllerTest {
    @Test
    void preparesANonCacheableSignedLinkWithoutChangingTheManualFileContract() {
        DownloadJobService jobs = mock(DownloadJobService.class);
        DownloadRequestOwner owners = mock(DownloadRequestOwner.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        UUID jobId = UUID.randomUUID();
        RequestOwner owner = new RequestOwner(null, "browser-hash", "ip-hash");
        URI signed = URI.create("https://downloads.example.test/zips/job.zip?signature=example");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(owners.resolve(null, "browser-token", "127.0.0.1")).thenReturn(owner);
        when(jobs.file(owner, jobId)).thenReturn(signed);
        DownloadJobController controller = new DownloadJobController(
                jobs,
                owners,
                mock(BundleRepository.class),
                mock(SseDownloadJobNotifier.class),
                mock(DownloadWorkerCapacityClient.class),
                false);

        var response = controller.fileLink(jobId, null, "browser-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().url()).isEqualTo(signed.toASCIIString());
    }
}

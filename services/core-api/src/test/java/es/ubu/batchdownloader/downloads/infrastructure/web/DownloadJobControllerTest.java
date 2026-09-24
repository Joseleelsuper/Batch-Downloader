package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.bundle.BundleRepository;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadJobAccessService;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.infrastructure.storage.DownloadDeliveryService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifica autorización de enlaces, streaming y confirmaciones de entrega. */
class DownloadJobControllerTest {
    private final DownloadJobAccessService access = mock(DownloadJobAccessService.class);
    private final DownloadRequestOwner owners = mock(DownloadRequestOwner.class);
    private final DownloadDeliveryService delivery = mock(DownloadDeliveryService.class);
    private final DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final UUID jobId = UUID.randomUUID();
    private final RequestOwner owner = new RequestOwner(null, "browser-hash", "ip-hash");
    private final DownloadJobController controller = new DownloadJobController(
            mock(DownloadJobService.class), access, owners, mock(BundleRepository.class),
            mock(SseDownloadJobNotifier.class), delivery, storage, false);

    @BeforeEach
    void owner() {
        request.setRemoteAddr("127.0.0.1");
        when(owners.resolve(null, "browser-token", "127.0.0.1")).thenReturn(owner);
    }

    @Test
    void preparesANonCacheableControlledLink() {
        var response = controller.fileLink(jobId, null, "browser-token", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().url()).isEqualTo("/api/v1/download-jobs/" + jobId + "/file");
        verify(access).get(owner, jobId);
        verify(delivery).requireAvailable(jobId);
    }

    @Test
    void authorizesStreamingActivityAndCompletion() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.file(jobId, null, "browser-token", request, response);
        verify(delivery).write(jobId, request, response);
        assertThat(controller.activity(jobId, new DownloadJobController.DownloadActivity("saving", 12L),
                null, "browser-token", request).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.complete(jobId, new DownloadJobController.DownloadCompletion(12L),
                null, "browser-token", request).getStatusCode().value()).isEqualTo(204);
        verify(storage).touch(jobId, "saving", 12);
        verify(storage).complete(jobId, 12);
    }

    @Test
    void inaccessibleJobCannotStartOrCompleteDelivery() {
        doThrow(new NotFoundException("download_job_not_found", "No existe el trabajo."))
                .when(access).get(owner, jobId);
        assertThatThrownBy(() -> controller.file(jobId, null, "browser-token", request,
                new MockHttpServletResponse())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.complete(jobId, new DownloadJobController.DownloadCompletion(12L),
                null, "browser-token", request)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(delivery);
        verify(storage, never()).complete(jobId, 12);
    }

    @Test
    void retriesConfirmedCompletionAfterTheJobWasPurged() {
        when(storage.confirmed(owner, jobId, 12)).thenReturn(true);
        assertThat(controller.complete(jobId, new DownloadJobController.DownloadCompletion(12L),
                null, "browser-token", request).getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(access);
        verify(storage, never()).complete(jobId, 12);
    }
}

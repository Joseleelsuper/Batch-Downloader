package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import es.ubu.batchdownloader.bundle.BundleRepository;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.downloads.application.*;
import es.ubu.batchdownloader.downloads.infrastructure.storage.DownloadDeliveryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DownloadPreviewControllerTest {
    private final DownloadJobService jobs = mock(DownloadJobService.class);
    private final DownloadRequestOwner owners = mock(DownloadRequestOwner.class);
    private final BundleRepository bundles = mock(BundleRepository.class);
    private final DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
    private final DownloadJobController controller = new DownloadJobController(jobs,
            mock(DownloadJobAccessService.class), owners, bundles, mock(SseDownloadJobNotifier.class),
            mock(DownloadDeliveryService.class), storage, false);

    @Test void anonymousPreviewDoesNotCreateAnOwnerCookieOrJob() {
        UUID id = UUID.randomUUID();
        controller.previewDownload(new DownloadJobController.CreateDownloadJobRequest(
                List.of(id), null, List.of("windows"), null, null, null, null), null);
        verify(jobs).preview(new DownloadSelection(List.of(id), List.of("windows"), null, null, null));
        verify(jobs, never()).create(any(), any());
        verifyNoInteractions(owners, storage);
    }

    @Test void aPrivateBundleRemainsInaccessibleDuringPreview() {
        when(bundles.appIdsForDownload("private", null, false)).thenThrow(new NotFoundException("bundle_not_found", "No existe."));
        assertThatThrownBy(() -> controller.previewDownload(new DownloadJobController.CreateDownloadJobRequest(
                null, "private", List.of("windows"), null, null, null, null), null)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(jobs, owners, storage);
    }
}

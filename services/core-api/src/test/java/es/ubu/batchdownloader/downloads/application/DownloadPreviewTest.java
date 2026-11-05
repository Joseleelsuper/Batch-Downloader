package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DownloadPreviewTest {
    private final DownloadJobStore jobs = mock(DownloadJobStore.class);
    private final DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
    private final CatalogSourceLookup sources = mock(CatalogSourceLookup.class);
    private final DownloadJobService service = new DownloadJobService(jobs, sources, Clock.systemUTC(),
            new DownloadLimits(100, Duration.ofHours(24), 10, 30), storage);

    private DownloadSelection selection(List<UUID> ids) {
        return new DownloadSelection(ids, List.of("windows"), null, null, null);
    }

    private CatalogSourceLookup.VerifiedSource installer(UUID app, UUID source) {
        return new CatalogSourceLookup.VerifiedSource(app, source, "windows", "x86_64", "App " + app, "https://example.com");
    }

    @Test void completeSelectionUsesRecordedSizesAndSourcesWithoutCreatingOrReservingJobs() {
        UUID app = UUID.randomUUID(), ref = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), any())).thenReturn(Map.of(app, installer(app, ref)));
        when(sources.sourceMetadata(any())).thenReturn(Map.of(ref,
                new CatalogSourceLookup.SourceMetadata(ref, "downloads.example.com", "app.exe", 1024L)));
        var preview = service.preview(selection(List.of(app)));
        assertThat(preview.knownBytes()).isEqualTo(1024);
        assertThat(preview.estimatedBytes()).isEqualTo(1024);
        assertThat(preview.estimated()).isFalse();
        assertThat(preview.unknownSizeCount()).isZero();
        assertThat(preview.items().getFirst().finalDomain()).isEqualTo("downloads.example.com");
        assertThat(preview.items().getFirst().sourceRef()).isEqualTo(ref);
        assertThat(preview.items().getFirst().filename()).isEqualTo("app.exe");
        assertThat(preview.estimatedPeakBytes()).isGreaterThan(2048);
        verifyNoInteractions(jobs, storage);
    }

    @Test void partialSelectionSeparatesEstimatesManualLinksAndOmissions() {
        UUID known = UUID.randomUUID(), unknown = UUID.randomUUID(), manual = UUID.randomUUID(), missing = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), any())).thenReturn(Map.of(known, installer(known, first), unknown, installer(unknown, second)));
        when(sources.findManualSources(any())).thenReturn(Map.of(manual,
                new CatalogSourceLookup.ManualSource(manual, "Manual app", "https://example.com")));
        when(sources.sourceMetadata(any())).thenReturn(Map.of(first,
                new CatalogSourceLookup.SourceMetadata(first, "example.com", "first.exe", 100L)));
        when(sources.appNames(any())).thenReturn(Map.of(missing, "Missing app"));
        var preview = service.preview(selection(List.of(known, unknown, manual, missing)));
        assertThat(preview.requestedCount()).isEqualTo(4);
        assertThat(preview.installerCount()).isEqualTo(2);
        assertThat(preview.manualCount()).isEqualTo(1);
        assertThat(preview.omittedCount()).isEqualTo(1);
        assertThat(preview.knownBytes()).isEqualTo(100);
        assertThat(preview.estimatedBytes()).isEqualTo(200);
        assertThat(preview.estimated()).isTrue();
        assertThat(preview.unknownSizeCount()).isEqualTo(1);
        assertThat(preview.items().get(1).warnings()).contains("size_unknown");
        assertThat(preview.items().get(2).warnings()).contains("manual_link");
        assertThat(preview.items().get(3).warnings()).contains("unavailable");
        assertThat(preview.items().get(3).name()).isEqualTo("Missing app");
        verifyNoInteractions(jobs, storage);
    }

    @Test void absentSizesRemainUnknownInsteadOfInventingATotal() {
        UUID app = UUID.randomUUID(), ref = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), any())).thenReturn(Map.of(app, installer(app, ref)));
        var preview = service.preview(selection(List.of(app)));
        assertThat(preview.knownBytes()).isZero();
        assertThat(preview.estimatedBytes()).isNull();
        assertThat(preview.estimatedPeakBytes()).isNull();
        assertThat(preview.estimated()).isFalse();
        assertThat(preview.items().getFirst().sizeBytes()).isNull();
        verifyNoInteractions(jobs, storage);
    }

    @Test void exactSourcesAndSelectionLimitsMatchJobAdmission() {
        UUID app = UUID.randomUUID(), ref = UUID.randomUUID();
        when(sources.findVerifiedSource(app, ref, List.of("windows"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.preview(new DownloadSelection(List.of(app), List.of("windows"), ref, null, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.preview(selection(List.of()))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.preview(selection(java.util.stream.IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID()).toList()))).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(jobs, storage);
    }

    @Test void oversizedEstimatesAreWarnedWithoutReservingCapacity() {
        UUID app = UUID.randomUUID(), ref = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), any())).thenReturn(Map.of(app, installer(app, ref)));
        when(sources.sourceMetadata(any())).thenReturn(Map.of(ref,
                new CatalogSourceLookup.SourceMetadata(ref, "example.com", "large.exe", 6L * 1024 * 1024 * 1024)));
        var preview = service.preview(selection(List.of(app)));
        assertThat(preview.warnings()).containsExactly("budget_estimate_exceeded");
        verifyNoInteractions(jobs, storage);
    }
}

package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RefreshingSourceRemoteDownloaderTest {
    @TempDir Path temporary;

    @Test
    void resolvesTheSameSourceOnceAfter618AndDownloadsWithFreshUrl() {
        var original = item();
        var fresh = new ResolvedDownloadItem(original.itemId(), original.appId(), original.sourceRef(),
                URI.create("https://github.com/vendor/app/releases/download/v1/App.exe"),
                original.filename(), original.operatingSystem(), original.architecture(), 4L, null, null);
        var delegate = mock(RemoteDownloader.class);
        var resolver = mock(SourceReferenceResolver.class);
        var request = new DownloadItemRequest(original.itemId(), original.appId(), original.sourceRef());
        when(resolver.resolve(request)).thenReturn(fresh);
        var target = temporary.resolve("App.exe");
        var budget = new DownloadBudget(100);
        var artifact = new DownloadedArtifact(original.itemId(), original.appId(), original.sourceRef(),
                "App.exe", target, 4, "a".repeat(64), null);
        when(delegate.download(original, "App.exe", target, budget, 100))
                .thenThrow(new DownloadRejectedException("remote_http_618"));
        when(delegate.download(fresh, "App.exe", target, budget, 100)).thenReturn(artifact);
        var downloader = new RefreshingSourceRemoteDownloader(delegate, resolver);
        assertThat(downloader.download(original, "App.exe", target, budget, 100)).isSameAs(artifact);
        verify(resolver, times(1)).resolve(request);
        verify(delegate).download(fresh, "App.exe", target, budget, 100);
    }

    @Test
    void stopsAfterOneRefreshEvenIfTheNewUrlAlsoFails() {
        var original = item();
        var resolver = mock(SourceReferenceResolver.class);
        when(resolver.resolve(any())).thenReturn(original);
        var delegate = mock(RemoteDownloader.class);
        when(delegate.download(any(), anyString(), any(), any(), anyLong()))
                .thenThrow(new DownloadRejectedException("remote_http_618"));
        var downloader = new RefreshingSourceRemoteDownloader(delegate, resolver);
        assertThatThrownBy(() -> downloader.download(original, "App.exe",
                temporary.resolve("App.exe"), new DownloadBudget(100), 100))
                .hasMessage("remote_http_618");
        verify(resolver, times(1)).resolve(any());
        verify(delegate, times(2)).download(any(), anyString(), any(), any(), anyLong());
    }

    @Test
    void doesNotRefreshOtherFailuresOrAcceptADifferentSource() {
        var original = item();
        var resolver = mock(SourceReferenceResolver.class);
        var delegate = mock(RemoteDownloader.class);
        when(delegate.download(any(), anyString(), any(), any(), anyLong()))
                .thenThrow(new DownloadRejectedException("remote_http_404"));
        var downloader = new RefreshingSourceRemoteDownloader(delegate, resolver);
        assertThatThrownBy(() -> downloader.download(original, "App.exe",
                temporary.resolve("App.exe"), new DownloadBudget(100), 100))
                .hasMessage("remote_http_404");
        verifyNoInteractions(resolver);
        doThrow(new DownloadRejectedException("remote_http_618")).when(delegate)
                .download(any(), anyString(), any(), any(), anyLong());
        when(resolver.resolve(any())).thenReturn(item());
        assertThatThrownBy(() -> downloader.download(original, "App.exe",
                temporary.resolve("App.exe"), new DownloadBudget(100), 100))
                .hasMessage("source_resolution_mismatch");
    }

    private ResolvedDownloadItem item() {
        return new ResolvedDownloadItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                URI.create("https://release-assets.githubusercontent.com/asset?jwt=expired"),
                "App.exe", "windows", "x86_64", 4L, null, null);
    }
}

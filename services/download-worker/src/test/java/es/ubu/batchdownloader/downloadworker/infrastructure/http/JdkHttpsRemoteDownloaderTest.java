package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class JdkHttpsRemoteDownloaderTest {
    @TempDir
    Path temp;

    @Test
    @SuppressWarnings("unchecked")
    void streamsHttpsResponseComputesHashAndHonorsBudget() throws Exception {
        byte[] content = "installer".getBytes();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(content.length));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        JdkHttpsRemoteDownloader downloader = downloader(client, DataSize.ofBytes(20));
        Path target = temp.resolve("App.exe");

        var artifact = downloader.download(item("https://downloads.example.com/App.exe"),
                "App.exe", target, new DownloadBudget(20), 20);

        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(artifact.sizeBytes()).isEqualTo(content.length);
        assertThat(artifact.sha256()).hasSize(64);
    }

    @Test
    void rejectsNonHttpsBeforeOpeningConnection() {
        HttpClient client = mock(HttpClient.class);
        JdkHttpsRemoteDownloader downloader = downloader(client, DataSize.ofMegabytes(1));

        assertThatThrownBy(() -> downloader.download(
                item("http://downloads.example.com/App.exe"),
                "App.exe",
                temp.resolve("App.exe"),
                new DownloadBudget(100),
                100))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("https_required");
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsDeclaredFileLargerThanConfiguredLimit() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(100));
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThatThrownBy(() -> downloader(client, DataSize.ofBytes(10)).download(
                item("https://downloads.example.com/App.exe"),
                "App.exe",
                temp.resolve("App.exe"),
                new DownloadBudget(100),
                10))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("file_size_limit_exceeded");
    }

    private JdkHttpsRemoteDownloader downloader(HttpClient client, DataSize maxFileSize) {
        DownloadProperties properties = new DownloadProperties(
                10,
                maxFileSize,
                DataSize.ofMegabytes(10),
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                1,
                Duration.ofMinutes(5),
                temp.toString());
        PublicHttpsUriPolicy policy = new PublicHttpsUriPolicy(host -> {
            try {
                return List.of(InetAddress.getByName("8.8.8.8"));
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        });
        return new JdkHttpsRemoteDownloader(client, policy, properties);
    }

    private ResolvedDownloadItem item(String url) {
        return new ResolvedDownloadItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                URI.create(url),
                "App.exe",
                "windows",
                "x86_64",
                null,
                null,
                null);
    }

    private HttpHeaders headers(long contentLength) {
        return HttpHeaders.of(
                Map.of("content-length", List.of(Long.toString(contentLength))),
                (name, value) -> true);
    }
}

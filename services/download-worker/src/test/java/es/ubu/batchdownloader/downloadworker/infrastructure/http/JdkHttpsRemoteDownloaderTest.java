package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

/**
 * Verifica validación, redirecciones, límites, MIME e integridad al transmitir instaladores con
 * respuestas HTTP simuladas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.JdkHttpsRemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de archivo y transporte
 */
class JdkHttpsRemoteDownloaderTest {
    /**
     * Dato compartido {@code temp} para los escenarios de prueba.
     */
    @TempDir
    Path temp;

    /**
     * Entrega una respuesta HTTPS que cabe en el presupuesto y comprueba los bytes escritos, el
     * tamaño y una huella SHA-256 de 64 caracteres.
     */
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

    /**
     * Propone una URL HTTP y exige https_required sin realizar llamadas al cliente de red.
     */
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

    /**
     * Declara 100 bytes frente a un límite de diez y comprueba file_size_limit_exceeded.
     */
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

    /**
     * Cambia entre tipos MIME binarios históricos y actuales y comprueba que se conservan los bytes
     * y el tamaño del instalador recibido.
     */
    @Test
    @SuppressWarnings("unchecked")
    void acceptsCurrentBinaryMimeWhenHistoricalMimeDiffers() throws Exception {
        byte[] content = "updated-installer".getBytes();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(content.length, "binary/octet-stream"));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        Path target = temp.resolve("Latest.exe");

        var artifact = downloader(client, DataSize.ofBytes(100)).download(
                item(
                        "https://downloads.example.com/Latest.exe",
                        null,
                        null,
                        "application/octet-stream"),
                "Latest.exe",
                target,
                new DownloadBudget(100),
                100);

        assertThat(artifact.sizeBytes()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
    }

    /**
     * Devuelve HTML con estado 200 y comprueba que se rechaza como
     * unexpected_download_content_type.
     */
    @Test
    @SuppressWarnings("unchecked")
    void stillRejectsHtmlInsteadOfAnInstaller() throws Exception {
        byte[] content = "<html>not an installer</html>".getBytes();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(content.length, "text/html; charset=utf-8"));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        assertThatThrownBy(() -> downloader(client, DataSize.ofBytes(100)).download(
                item("https://downloads.example.com/Latest.exe"),
                "Latest.exe",
                temp.resolve("Latest.exe"),
                new DownloadBudget(100),
                100))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("unexpected_download_content_type");
    }

    /**
     * Actualiza el tamaño de una fuente sin huella fijada y comprueba que se acepta el contenido
     * actual.
     */
    @Test
    @SuppressWarnings("unchecked")
    void acceptsChangedLatestInstallerSizeWhenNoDigestPinsTheArtifact() throws Exception {
        byte[] content = "updated-installer".getBytes();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(content.length));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        Path target = temp.resolve("Latest.exe");

        var artifact = downloader(client, DataSize.ofBytes(100)).download(
                item("https://downloads.example.com/Latest.exe", 8L, null),
                "Latest.exe",
                target,
                new DownloadBudget(100),
                100);

        assertThat(artifact.sizeBytes()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
    }

    /**
     * Proporciona un tamaño histórico distinto y un SHA-256 coincidente con el contenido actual y
     * comprueba que la descarga se conserva.
     */
    @Test
    @SuppressWarnings("unchecked")
    void acceptsChangedInstallerSizeWhenDigestStillMatches() throws Exception {
        byte[] content = "different-installer".getBytes();
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(content.length));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        Path target = temp.resolve("Pinned.exe");

        var artifact = downloader(client, DataSize.ofBytes(100)).download(
                item(
                        "https://downloads.example.com/Pinned.exe",
                        8L,
                        sha256),
                "Pinned.exe",
                target,
                new DownloadBudget(100),
                100);

        assertThat(artifact.sizeBytes()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
    }

    /**
     * Proporciona una huella distinta de la calculada y comprueba source_sha256_mismatch y
     * eliminación del archivo de destino.
     */
    @Test
    @SuppressWarnings("unchecked")
    void stillRejectsInstallerWhenDigestDoesNotMatch() throws Exception {
        byte[] content = "different-installer".getBytes();
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers(content.length));
        when(response.body()).thenReturn(new ByteArrayInputStream(content));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        Path target = temp.resolve("Pinned.exe");
        assertThatThrownBy(() -> downloader(client, DataSize.ofBytes(100)).download(
                item(
                        "https://downloads.example.com/Pinned.exe",
                        8L,
                        "0000000000000000000000000000000000000000000000000000000000000000"),
                "Pinned.exe",
                target,
                new DownloadBudget(100),
                100))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("source_sha256_mismatch");
        assertThat(target).doesNotExist();
    }

    /**
     * Devuelve una redirección relativa seguida de éxito y comprueba el orden y las dos URI
     * solicitadas explícitamente.
     */
    @Test
    @SuppressWarnings("unchecked")
    void followsRedirectsOneHopAtATime() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> redirect = mock(HttpResponse.class);
        when(redirect.statusCode()).thenReturn(302);
        when(redirect.headers()).thenReturn(HttpHeaders.of(
                Map.of("location", List.of("/releases/App.exe")),
                (name, value) -> true));
        when(redirect.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        HttpResponse<java.io.InputStream> success = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(200);
        when(success.headers()).thenReturn(headers(3));
        when(success.body()).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(client.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(redirect, success);

        downloader(client, DataSize.ofBytes(10)).download(
                item("https://downloads.example.com/latest"),
                "App.exe",
                temp.resolve("App.exe"),
                new DownloadBudget(10),
                10);

        ArgumentCaptor<java.net.http.HttpRequest> requests =
                ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues())
                .extracting(request -> request.uri().toString())
                .containsExactly(
                        "https://downloads.example.com/latest",
                        "https://downloads.example.com/releases/App.exe");
    }

    /**
     * Compone el descargador con un cliente simulado, límite controlado y DNS público fijo para
     * evitar consultas externas.
     *
     * @param client cliente HTTP simulado que proporciona las respuestas del escenario.
     * @param maxFileSize límite por archivo, con su unidad de tamaño.
     * @return descargador listo para consumir las respuestas del escenario.
     */
    private JdkHttpsRemoteDownloader downloader(HttpClient client, DataSize maxFileSize) {
        DownloadProperties properties = new DownloadProperties(
                10,
                maxFileSize,
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
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

    /**
     * Construye una fuente Windows de prueba y conserva las expectativas opcionales de tamaño,
     * huella y MIME recibidas por la sobrecarga.
     *
     * @param url dirección original del instalador, válida o inválida según el escenario.
     * @return fuente resuelta con identidades aleatorias y nombre App.exe.
     */
    private ResolvedDownloadItem item(String url) {
        return item(url, null, null, null);
    }

    /**
     * Construye una fuente Windows de prueba y conserva las expectativas opcionales de tamaño,
     * huella y MIME recibidas por la sobrecarga.
     *
     * @param url dirección original del instalador, válida o inválida según el escenario.
     * @param expectedSizeBytes tamaño histórico en bytes; null indica que no se conoce.
     * @param expectedSha256 SHA-256 fijado para la fuente; null permite cambios de contenido.
     * @return fuente resuelta con identidades aleatorias y nombre App.exe.
     */
    private ResolvedDownloadItem item(String url, Long expectedSizeBytes, String expectedSha256) {
        return item(url, expectedSizeBytes, expectedSha256, null);
    }

    /**
     * Construye una fuente Windows de prueba y conserva las expectativas opcionales de tamaño,
     * huella y MIME recibidas por la sobrecarga.
     *
     * @param url dirección original del instalador, válida o inválida según el escenario.
     * @param expectedSizeBytes tamaño histórico en bytes; null indica que no se conoce.
     * @param expectedSha256 SHA-256 fijado para la fuente; null permite cambios de contenido.
     * @param expectedMime tipo MIME histórico; null indica que no se conoce.
     * @return fuente resuelta con identidades aleatorias y nombre App.exe.
     */
    private ResolvedDownloadItem item(
            String url,
            Long expectedSizeBytes,
            String expectedSha256,
            String expectedMime) {
        return new ResolvedDownloadItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                URI.create(url),
                "App.exe",
                "windows",
                "x86_64",
                expectedSizeBytes,
                expectedSha256,
                expectedMime);
    }

    /**
     * Construye las cabeceras de una respuesta simulada y omite Content-Type cuando no se
     * proporciona.
     *
     * @param contentLength longitud en bytes declarada por el servidor.
     * @return cabeceras HTTP con la longitud declarada y el MIME opcional.
     */
    private HttpHeaders headers(long contentLength) {
        return headers(contentLength, null);
    }

    /**
     * Construye las cabeceras de una respuesta simulada y omite Content-Type cuando no se
     * proporciona.
     *
     * @param contentLength longitud en bytes declarada por el servidor.
     * @param contentType tipo MIME de la respuesta; null omite la cabecera.
     * @return cabeceras HTTP con la longitud declarada y el MIME opcional.
     */
    private HttpHeaders headers(long contentLength, String contentType) {
        Map<String, List<String>> values = contentType == null
                ? Map.of("content-length", List.of(Long.toString(contentLength)))
                : Map.of(
                        "content-length", List.of(Long.toString(contentLength)),
                        "content-type", List.of(contentType));
        return HttpHeaders.of(
                values,
                (name, value) -> true);
    }
}

package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Caracteriza reintentos, consumo del presupuesto y límite simultáneo de descargas por host.
 *
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.RetryingRemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.http.HostLimitedRemoteDownloader
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadBudget
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de archivo y transporte
 */
class DownloadConcurrencyPoliciesTest {
    @TempDir Path temporary;

    /**
     * Simula dos respuestas 429 y un éxito y exige tres intentos y el cargo de los 64 bytes del
     * resultado al presupuesto total.
     */
    @Test
    void retriesOnlyTwiceAndCommitsTheSuccessfulAttemptToTheTotalBudget() {
        AtomicInteger calls = new AtomicInteger();
        RemoteDownloader delegate = (item, filename, target, budget, maximum) -> {
            if (calls.getAndIncrement() < 2) {
                throw new DownloadRejectedException("remote_http_429", Duration.ZERO);
            }
            budget.consume(64);
            return artifact(item, filename, target, 64);
        };
        DownloadBudget total = new DownloadBudget(1024);
        RetryingRemoteDownloader downloader = new RetryingRemoteDownloader(
                delegate, new SimpleMeterRegistry());

        DownloadedArtifact result = downloader.download(
                item("downloads.example"), "setup.exe", temporary.resolve("setup.exe"), total, 512);

        assertThat(calls).hasValue(3);
        assertThat(result.sizeBytes()).isEqualTo(64);
        assertThat(total.consumedBytes()).isEqualTo(64);
    }

    /**
     * Simula un 404 y comprueba que se propaga el rechazo tras un único intento.
     */
    @Test
    void doesNotRetryARegularClientError() {
        AtomicInteger calls = new AtomicInteger();
        RemoteDownloader delegate = (item, filename, target, budget, maximum) -> {
            calls.incrementAndGet();
            throw new DownloadRejectedException("remote_http_404");
        };
        RetryingRemoteDownloader downloader = new RetryingRemoteDownloader(
                delegate, new SimpleMeterRegistry());

        assertThatThrownBy(() -> downloader.download(
                        item("downloads.example"), "setup.exe", temporary.resolve("setup.exe"),
                        new DownloadBudget(1024), 512))
                .isInstanceOf(DownloadRejectedException.class)
                .hasMessage("remote_http_404");
        assertThat(calls).hasValue(1);
    }

    /**
     * Lanza tres descargas al mismo host y utiliza barreras y un contador para comprobar que el
     * máximo de ejecuciones simultáneas es dos.
     */
    @Test
    void allowsAtMostTwoActiveDownloadsForTheSameHost() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch pairEntered = new CountDownLatch(2);
        RemoteDownloader delegate = (item, filename, target, budget, limit) -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            pairEntered.countDown();
            try {
                pairEntered.await(1, TimeUnit.SECONDS);
                Thread.sleep(50);
                return artifact(item, filename, target, 1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DownloadRejectedException("download_interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        };
        HostLimitedRemoteDownloader downloader = new HostLimitedRemoteDownloader(
                delegate, new SimpleMeterRegistry(), 2);

        CompletableFuture<?>[] tasks = java.util.stream.IntStream.range(0, 3)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> downloader.download(
                        item("same.example"),
                        "setup-" + index + ".exe",
                        temporary.resolve("setup-" + index + ".exe"),
                        new DownloadBudget(10),
                        10)))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(tasks).get(2, TimeUnit.SECONDS);

        assertThat(maximum).hasValue(2);
    }

    /**
     * Crea una fuente Windows que dirige la descarga al host cuyo semáforo se quiere comprobar.
     *
     * @param host host inicial que comparte el límite de concurrencia.
     * @return fuente de prueba con tamaño histórico de un byte.
     */
    private ResolvedDownloadItem item(String host) {
        return new ResolvedDownloadItem(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                URI.create("https://" + host + "/setup.exe"),
                "setup.exe", "windows", "x86_64", 1L, null, null);
    }

    /**
     * Representa el resultado del doble de descarga sin escribir contenido en disco.
     *
     * @param item fuente resuelta de la descarga simulada.
     * @param filename nombre de salida del instalador.
     * @param target archivo temporal de destino.
     * @param size tamaño en bytes del artefacto simulado.
     * @return metadatos de prueba con identidades de la fuente y una huella fija.
     */
    private DownloadedArtifact artifact(
            ResolvedDownloadItem item, String filename, Path target, long size) {
        return new DownloadedArtifact(
                item.itemId(), item.appId(), item.sourceRef(), filename, target,
                size, "a".repeat(64), null);
    }
}

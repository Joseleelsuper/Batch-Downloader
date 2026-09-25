package es.ubu.batchdownloader.downloads.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Verifica transporte, rangos y liberación de referencias ante errores y cancelación. */
class DownloadDeliveryServiceTest {
    private final UUID jobId = UUID.randomUUID();
    private final DownloadJobStore jobs = mock(DownloadJobStore.class);
    private final DownloadStorageCoordinator storage = mock(DownloadStorageCoordinator.class);
    private final MinioClient minio = mock(MinioClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
    private final DownloadDeliveryService service = new DownloadDeliveryService(jobs, storage, minio, "zips");
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/file");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void artifact() throws Exception {
        DownloadJob job = mock(DownloadJob.class);
        when(job.id()).thenReturn(jobId);
        when(job.status()).thenReturn(DownloadJobStatus.READY);
        when(job.objectKey()).thenReturn("job.zip");
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(storage.transferAllowed(jobId)).thenReturn(true);
        StatObjectResponse metadata = mock(StatObjectResponse.class);
        when(metadata.size()).thenReturn(6L);
        when(metadata.etag()).thenReturn("digest");
        when(metadata.lastModified()).thenReturn(ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        when(minio.statObject(any(StatObjectArgs.class))).thenReturn(metadata);
        when(minio.getObject(any(GetObjectArgs.class))).thenAnswer(invocation -> {
            GetObjectArgs args = invocation.getArgument(0);
            return source(new ByteArrayInputStream("abcdef".substring(Math.toIntExact(args.offset()),
                    Math.toIntExact(args.offset() + args.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        });
    }

    @Test
    void streamsFullArtifactAndRecordsProgress() throws Exception {
        service.write(jobId, request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("abcdef");
        assertThat(response.getContentLengthLong()).isEqualTo(6);
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        verify(storage).transferStarted(eq(jobId), any(UUID.class));
        verify(storage).transferProgress(jobId, 6);
        verify(storage).transferFinished(eq(jobId), any(UUID.class));
    }

    @ParameterizedTest
    @CsvSource({"bytes=1-3,bcd,bytes 1-3/6,4", "bytes=3-,def,bytes 3-5/6,6", "bytes=-2,ef,bytes 4-5/6,6"})
    void resumesSingleRanges(String range, String body, String contentRange, long progress) throws Exception {
        request.addHeader(HttpHeaders.RANGE, range);
        request.addHeader(HttpHeaders.IF_RANGE, "\"digest\"");
        service.write(jobId, request, response);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo(contentRange);
        assertThat(response.getContentAsString()).isEqualTo(body);
        assertThat(response.getContentLengthLong()).isEqualTo(body.length());
        verify(storage).transferProgress(jobId, progress);
    }

    @Test
    void staleIfRangeReturnsTheWholeArtifact() throws Exception {
        request.addHeader(HttpHeaders.RANGE, "bytes=2-");
        request.addHeader(HttpHeaders.IF_RANGE, "\"old\"");
        service.write(jobId, request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("abcdef");
    }

    @Test
    void headReturnsMetadataWithoutOpeningOrKeepingAliveATransfer() throws Exception {
        request.setMethod("HEAD");
        request.addHeader(HttpHeaders.RANGE, "bytes=1-3");
        service.write(jobId, request, response);
        assertThat(response.getContentLengthLong()).isEqualTo(6);
        assertThat(response.getContentAsByteArray()).isEmpty();
        verify(minio, never()).getObject(any(GetObjectArgs.class));
        verify(storage, never()).transferStarted(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"bytes=6-", "bytes=-0", "'bytes=0-1,3-4'", "bytes=invalid"})
    void rejectsUnsatisfiableOrMultipleRanges(String range) throws Exception {
        request.addHeader(HttpHeaders.RANGE, range);
        service.write(jobId, request, response);
        assertThat(response.getStatus()).isEqualTo(416);
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */6");
        verify(minio, never()).getObject(any(GetObjectArgs.class));
    }

    @Test
    void prematureEndClosesTheSourceAndFinishesTheTransfer() throws Exception {
        ByteArrayInputStream input = spy(new ByteArrayInputStream(new byte[] {1}));
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(source(input));
        assertThatThrownBy(() -> service.write(jobId, request, response)).isInstanceOf(IOException.class);
        verify(input).close();
        verify(storage).transferFinished(eq(jobId), any(UUID.class));
        verify(storage, never()).transferProgress(jobId, 6);
    }

    @Test
    void watchdogClosesBlockedInputWhenCleanupCancelsTheTransfer() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        InputStream blocked = new InputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                try {
                    if (!closed.await(5, TimeUnit.SECONDS)) throw new IOException("Timeout de la prueba.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return -1;
            }
            @Override
            public void close() { closed.countDown(); }
        };
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(source(blocked));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var transfer = executor.submit(() -> {
                service.write(jobId, request, response);
                return null;
            });
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            when(storage.transferAllowed(jobId)).thenReturn(false);
            service.closeInactiveTransfers();
            assertThatThrownBy(() -> transfer.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            verify(storage).transferFinished(eq(jobId), any(UUID.class));
        }
    }

    @Test
    void concurrentClosersCloseTheInputOnceWithoutReleasingAnActiveReader() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch finishReading = new CountDownLatch(1);
        AtomicInteger closures = new AtomicInteger();
        InputStream blocked = new InputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                try {
                    if (!finishReading.await(5, TimeUnit.SECONDS)) throw new IOException("Timeout de la prueba.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
                return -1;
            }

            @Override
            public void close() { closures.incrementAndGet(); }
        };
        when(minio.getObject(any(GetObjectArgs.class))).thenReturn(source(blocked));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var transfer = executor.submit(() -> {
                service.write(jobId, request, response);
                return null;
            });
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                when(storage.transferAllowed(jobId)).thenReturn(false);
                var watchdog = executor.submit(service::closeInactiveTransfers);
                var shutdown = executor.submit(service::close);
                watchdog.get(5, TimeUnit.SECONDS);
                shutdown.get(5, TimeUnit.SECONDS);
                assertThat(closures).hasValue(1);
                verify(storage, never()).transferFinished(any(), any());
            } finally {
                finishReading.countDown();
            }
            assertThatThrownBy(() -> transfer.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            verify(storage).transferFinished(eq(jobId), any(UUID.class));
        }
    }

    @Test
    void retriesFailedReleaseWithTheSameTransferIdentity() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .doNothing().when(storage).transferFinished(eq(jobId), any(UUID.class));
        service.write(jobId, request, response);
        service.closeInactiveTransfers();
        service.closeInactiveTransfers();

        ArgumentCaptor<UUID> started = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> finished = ArgumentCaptor.forClass(UUID.class);
        verify(storage).transferStarted(eq(jobId), started.capture());
        verify(storage, times(2)).transferFinished(eq(jobId), finished.capture());
        assertThat(finished.getAllValues()).containsOnly(started.getValue());
        assertThat(response.getContentAsString()).isEqualTo("abcdef");
    }

    @Test
    void releasesAnAmbiguousStartEvenWhenNoBytesWereRead() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("start response lost"))
                .when(storage).transferStarted(eq(jobId), any(UUID.class));
        assertThatThrownBy(() -> service.write(jobId, request, response))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        ArgumentCaptor<UUID> started = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<UUID> finished = ArgumentCaptor.forClass(UUID.class);
        verify(storage).transferStarted(eq(jobId), started.capture());
        verify(storage).transferFinished(eq(jobId), finished.capture());
        assertThat(finished.getValue()).isEqualTo(started.getValue());
        verify(minio, never()).getObject(any(GetObjectArgs.class));
    }

    private GetObjectResponse source(InputStream input) {
        return new GetObjectResponse(Headers.of(), "zips", "us-east-1", "job.zip", input);
    }
}

package es.ubu.batchdownloader.downloads.infrastructure.storage;

import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Entrega ZIP mediante streaming observable sin retener una transacción durante la transferencia. */
@Service
public class DownloadDeliveryService {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadDeliveryService.class);
    private static final long STALL_NANOS = Duration.ofMinutes(5).toNanos();
    private final DownloadJobStore jobs;
    private final DownloadStorageCoordinator storage;
    private final MinioClient minio;
    private final String bucket;
    private final Set<Transfer> transfers = ConcurrentHashMap.newKeySet();

    public DownloadDeliveryService(DownloadJobStore jobs, DownloadStorageCoordinator storage,
            MinioClient minio, @Value("${app.minio.bucket}") String bucket) {
        this.jobs = jobs;
        this.storage = storage;
        this.minio = minio;
        this.bucket = bucket;
    }

    /** Comprueba disponibilidad después de que el controlador haya autorizado al propietario. */
    public void requireAvailable(UUID jobId) {
        downloadable(jobId);
    }

    /** GET admite un rango; HEAD informa del archivo completo sin abrir una transferencia. */
    public void write(UUID jobId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        DownloadJob job = downloadable(jobId);
        StatObjectResponse metadata;
        try {
            metadata = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(job.objectKey()).build());
        } catch (Exception exception) {
            throw new ServiceUnavailableException("download_unavailable", "No se pudo abrir el ZIP.", 5);
        }
        long size = metadata.size();
        String etag = "\"" + metadata.etag() + "\"";
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.ETAG, etag);
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, metadata.lastModified().toInstant().toEpochMilli());
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"batch-downloader-" + jobId + ".zip\"");
        response.setContentType("application/zip");
        boolean head = "HEAD".equals(request.getMethod());
        long start = 0;
        long end = size - 1;
        String range = request.getHeader(HttpHeaders.RANGE);
        if (!head && range != null && ifRangeMatches(request, etag, metadata)) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1 || size == 0) throw new IllegalArgumentException("single_range_required");
                start = ranges.getFirst().getRangeStart(size);
                end = ranges.getFirst().getRangeEnd(size);
                if (start >= size || start > end) throw new IllegalArgumentException("unsatisfiable_range");
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
            } catch (IllegalArgumentException exception) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                response.setContentLengthLong(0);
                return;
            }
        }
        long length = end - start + 1;
        response.setContentLengthLong(length);
        if (head) return;
        transfer(job, start, length, response.getOutputStream());
    }

    private void transfer(DownloadJob job, long start, long length, OutputStream output) throws IOException {
        Transfer transfer = new Transfer(job.id(), output);
        transfers.add(transfer);
        try {
            storage.transferStarted(job.id(), transfer.id);
            try (InputStream input = minio.getObject(GetObjectArgs.builder().bucket(bucket)
                    .object(job.objectKey()).offset(start).length(length).build())) {
                transfer.input.set(input);
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                long reportedAt = System.nanoTime();
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("El ZIP terminó antes del tamaño anunciado.");
                    output.write(buffer, 0, count);
                    remaining -= count;
                    transfer.progressAt = System.nanoTime();
                    if (remaining == 0 || transfer.progressAt - reportedAt >= Duration.ofSeconds(1).toNanos()) {
                        if (!storage.transferAllowed(job.id())) throw new IOException("La entrega se ha cancelado.");
                        storage.transferProgress(job.id(), start + length - remaining);
                        reportedAt = transfer.progressAt;
                    }
                }
                output.flush();
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("No se pudo transferir el ZIP.", exception);
        } finally {
            transfer.finished = true;
            finish(transfer);
        }
    }

    /** Reintenta el mismo recibo si SQL falla, incluso si START confirmó sin devolver respuesta. */
    private void finish(Transfer transfer) {
        synchronized (transfer.completionLock) {
            if (!transfers.contains(transfer)) return;
            try {
                storage.transferFinished(transfer.jobId, transfer.id);
                transfers.remove(transfer);
            } catch (RuntimeException exception) {
                LOG.warn("Download transfer release pending job={} transfer={}",
                        transfer.jobId, transfer.id, exception);
            }
        }
    }

    /** Interrumpe sockets bloqueados; la reserva sigue ocupada hasta salir del bloque de transferencia. */
    @Scheduled(fixedDelay = 10_000)
    public void closeInactiveTransfers() {
        long now = System.nanoTime();
        for (Transfer transfer : transfers) {
            if (transfer.finished) {
                finish(transfer);
            } else {
                try {
                    if (now - transfer.progressAt >= STALL_NANOS || !storage.transferAllowed(transfer.jobId)) {
                        transfer.close();
                    }
                } catch (RuntimeException exception) {
                    LOG.warn("Download transfer activity check deferred job={}", transfer.jobId, exception);
                }
            }
        }
    }

    @PreDestroy
    public void close() {
        transfers.forEach(Transfer::close);
    }

    private DownloadJob downloadable(UUID jobId) {
        DownloadJob job = jobs.findById(jobId)
                .orElseThrow(() -> new NotFoundException("download_job_not_found", "No existe el trabajo."));
        if (!job.status().downloadable() || job.objectKey() == null || !storage.transferAllowed(jobId)) {
            throw new ConflictException("download_not_ready", "El ZIP no está disponible.");
        }
        return job;
    }

    private boolean ifRangeMatches(HttpServletRequest request, String etag, StatObjectResponse metadata) {
        String value = request.getHeader(HttpHeaders.IF_RANGE);
        if (value == null) return true;
        if (value.startsWith("\"") || value.startsWith("W/")) return etag.equals(value);
        try {
            return metadata.lastModified().toEpochSecond()
                    <= ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
        } catch (java.time.format.DateTimeParseException exception) {
            return false;
        }
    }

    private static final class Transfer {
        private final UUID id = UUID.randomUUID();
        private final UUID jobId;
        private final OutputStream output;
        private final Object completionLock = new Object();
        private final AtomicReference<InputStream> input = new AtomicReference<>();
        private volatile long progressAt = System.nanoTime();
        private volatile boolean finished;

        private Transfer(UUID jobId, OutputStream output) {
            this.jobId = jobId;
            this.output = output;
        }

        private void close() {
            try {
                InputStream current = input.getAndSet(null);
                if (current != null) current.close();
            } catch (IOException ignored) {
                // El cierre del otro extremo puede haber interrumpido ya el socket.
            }
            try {
                output.close();
            } catch (IOException ignored) {
                // La transferencia libera su referencia en finally incluso tras una desconexión.
            }
        }
    }
}

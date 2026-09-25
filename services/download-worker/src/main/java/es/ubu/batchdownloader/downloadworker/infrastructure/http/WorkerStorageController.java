package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Limpieza autenticada e inventario real utilizados por el ledger de Core. */
@RestController
final class WorkerStorageController {
    private final DownloadJobProcessor processor;
    private final CoreApiProperties core;
    private final DownloadProperties properties;

    WorkerStorageController(DownloadJobProcessor processor, CoreApiProperties core, DownloadProperties properties) {
        this.processor = processor;
        this.core = core;
        this.properties = properties;
    }

    @DeleteMapping("/internal/v1/jobs/{jobId}/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void clean(@PathVariable UUID jobId,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token) {
        authenticate(token);
        processor.clean(jobId);
    }

    @GetMapping("/internal/v1/storage/inventory")
    Inventory inventory(@RequestHeader(value = "X-Internal-Service-Token", required = false) String token)
            throws IOException {
        authenticate(token);
        var usage = processor.usage();
        processor.activeJobs().forEach(id -> usage.putIfAbsent(id, 0L));
        Path directory = Path.of(properties.tempDirectory());
        Files.createDirectories(directory);
        return new Inventory(usage.entrySet().stream()
                .map(entry -> new Job(entry.getKey(), entry.getValue(), processor.active(entry.getKey()))).toList(),
                Math.max(0, Files.getFileStore(directory).getUsableSpace() - properties.minFreeSpace().toBytes()));
    }

    private void authenticate(String token) {
        if (token == null || core.serviceToken().isBlank() || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), core.serviceToken().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_internal_token");
        }
    }

    record Job(UUID jobId, long bytes, boolean active) {}
    record Inventory(List<Job> jobs, long availableBytes) {}
}

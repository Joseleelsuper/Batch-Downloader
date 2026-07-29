package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadJobService.DownloadItemMetadata;
import es.ubu.batchdownloader.common.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/download-jobs")
public class InternalDownloadJobMetadataController {
    private final DownloadJobService jobs;
    private final byte[] expectedToken;

    public InternalDownloadJobMetadataController(
            DownloadJobService jobs,
            @Value("${app.scraper-internal-service-token}") String internalServiceToken) {
        this.jobs = jobs;
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{jobId}/item-metadata")
    List<DownloadItemMetadata> itemMetadata(
            @PathVariable UUID jobId,
            @Valid @RequestBody DownloadItemMetadataRequest request,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String suppliedToken) {
        requireInternalToken(suppliedToken);
        return jobs.itemMetadata(jobId, request.itemIds());
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (suppliedToken == null || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new UnauthorizedException(
                    "internal_service_token_invalid",
                    "La credencial interna no es válida.");
        }
    }

    record DownloadItemMetadataRequest(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> itemIds) {}

}

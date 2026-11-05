package es.ubu.batchdownloader.softwarerequest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contrato de propuestas de software y decisiones de revisión, sin datos de contacto. */
public final class SoftwareRequestDtos {
    private SoftwareRequestDtos() {}

    public record CreateSoftwareRequest(@NotBlank @Size(max = 160) String name,
            @NotBlank @Size(max = 2048) String officialUrl) {}

    public record ReviewSoftwareRequest(@NotBlank String status,
            @NotBlank @Size(max = 1000) String reviewNote, @NotNull @Min(0) Long version) {}

    public record SoftwareRequest(UUID id, String name, String officialUrl, String status,
            String reviewNote, Instant createdAt, Instant reviewedAt, long version) {}

    public record SoftwareRequestPage(List<SoftwareRequest> data, int page, int pageSize, long total) {}
}

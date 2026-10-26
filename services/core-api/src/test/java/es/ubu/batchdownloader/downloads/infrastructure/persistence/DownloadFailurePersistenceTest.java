package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

/** Conserva el diagnóstico en las columnas existentes y en la vista pública tras recargarlo. */
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class DownloadFailurePersistenceTest {
    @Autowired private SpringDataDownloadJobRepository repository;
    @Autowired private EntityManager entities;

    @Test
    void reloadsTheProviderFailureIntoThePublicJobView() {
        Instant now = Instant.parse("2026-10-26T12:00:00Z");
        DownloadJobItem item = DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), now);
        DownloadJob job = DownloadJob.queue(UUID.randomUUID(), null, null,
                List.of(item), 1, 0, now, now.plusSeconds(3600));
        job.updateItem(item.id(), DownloadItemStatus.FAILED, 0, null,
                "source_revalidation_http_618", now.plusSeconds(1));
        repository.saveAndFlush(DownloadJobEntity.from(job));
        entities.clear();

        DownloadJob reloaded = repository.findById(job.id()).orElseThrow().toDomain();

        assertThat(reloaded.items().getFirst().errorCode()).isEqualTo("source_revalidation_http_618");
        assertThat(DownloadJobView.from(reloaded).items().getFirst().errorCode())
                .isEqualTo("source_revalidation_http_618");
    }
}

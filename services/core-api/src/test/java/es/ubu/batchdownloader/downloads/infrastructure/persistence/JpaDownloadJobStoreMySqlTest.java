package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** El progreso JDBC debe refrescar el agregado que Hibernate ya cargó en la misma transacción. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.minimum-idle=1", "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.connection-timeout=2000"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaDownloadJobStore.class)
class JpaDownloadJobStoreMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Autowired private JpaDownloadJobStore store;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Test
    void progressReturnsFreshJobAndItemsAfterTheirPreviousSnapshotWasRead() {
        Instant now = Instant.parse("2026-09-24T15:00:00Z");
        DownloadJobItem item = DownloadJobItem.queued(
                UUID.randomUUID(), UUID.randomUUID(), "App", "https://example.test", now);
        DownloadJob job = store.save(DownloadJob.queue(
                UUID.randomUUID(), null, null, List.of(item), 1, 0, now, now.plusSeconds(3600)));
        assertThat(store.findById(job.id()).orElseThrow().items().getFirst().bytesDownloaded()).isZero();

        DownloadJob result = store.applyProgress(job.id(), item.id(), DownloadItemStatus.DOWNLOADING,
                4096, null, null, now.plusSeconds(1)).orElseThrow();
        assertThat(result.status()).isEqualTo(DownloadJobStatus.DOWNLOADING);
        assertThat(result.items().getFirst().status()).isEqualTo(DownloadItemStatus.DOWNLOADING);
        assertThat(result.items().getFirst().bytesDownloaded()).isEqualTo(4096);

        result = store.applyProgress(job.id(), item.id(), DownloadItemStatus.COMPLETED,
                8192, "a".repeat(64), null, now.plusSeconds(2)).orElseThrow();
        assertThat(result.status()).isEqualTo(DownloadJobStatus.PACKAGING);
        assertThat(result.progress()).isEqualTo(90);
        assertThat(result.items().getFirst().status()).isEqualTo(DownloadItemStatus.COMPLETED);
        assertThat(result.items().getFirst().bytesDownloaded()).isEqualTo(8192);
    }
}

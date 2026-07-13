package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcInboxRepositoryTest {
    private JdbcInboxRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE download_inbox (
                    event_id VARCHAR(64) PRIMARY KEY,
                    status VARCHAR(24) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE NULL
                )
                """);
        repository = new JdbcInboxRepository(jdbc, Clock.systemUTC());
    }

    @Test
    void processesAnEventOnlyOnceAfterCompletion() {
        UUID eventId = UUID.randomUUID();

        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isFalse();
        repository.complete(eventId);
        assertThat(repository.tryStart(eventId, Duration.ZERO)).isFalse();
    }

    @Test
    void releaseAllowsRabbitRetryToClaimAgain() {
        UUID eventId = UUID.randomUUID();
        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();

        repository.release(eventId);

        assertThat(repository.tryStart(eventId, Duration.ofMinutes(30))).isTrue();
    }
}

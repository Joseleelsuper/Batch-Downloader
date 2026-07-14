package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class JpaCatalogSourceLookupTest {

    @Test
    void choosesFreshFirstButKeepsRecentValidatedCandidateForRevalidation() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-13T16:00:00Z"), ZoneOffset.UTC);
        JpaCatalogSourceLookup lookup = new JpaCatalogSourceLookup(
                jdbc, clock, Duration.ofDays(7));

        assertThat(lookup.findVerifiedSources(List.of(UUID.randomUUID()), List.of("windows")))
                .isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), parameters.capture());
        assertThat(sql.getValue()).contains("rs.checked_at >= ?");
        assertThat(sql.getValue()).contains("(rs.expires_at > NOW()) DESC");
        assertThat(parameters.getValue()).anyMatch(Timestamp.class::isInstance);
        assertThat(parameters.getValue()).contains("windows");
    }
}

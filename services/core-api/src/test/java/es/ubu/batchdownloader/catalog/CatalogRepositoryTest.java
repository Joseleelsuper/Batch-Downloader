package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CatalogRepositoryTest {
    @Test
    void facetLetterGroupsLatinLettersAndNonLatinPrefixes() {
        assertThat(CatalogRepository.facetLetter(".NET")).isEqualTo("N");
        assertThat(CatalogRepository.facetLetter("Álvaro Tools")).isEqualTo("A");
        assertThat(CatalogRepository.facetLetter("4t Niagara Software")).isEqualTo("#");
        assertThat(CatalogRepository.facetLetter("東Vendor")).isEqualTo("#");
    }

    @Test
    void requiredTagMatchesDefaultsToAllAndClampsExplicitValues() {
        assertThat(CatalogRepository.requiredTagMatches(3, null, "all")).isEqualTo(3);
        assertThat(CatalogRepository.requiredTagMatches(3, null, "any")).isEqualTo(1);
        assertThat(CatalogRepository.requiredTagMatches(3, 9, "all")).isEqualTo(3);
        assertThat(CatalogRepository.requiredTagMatches(3, 0, "all")).isEqualTo(1);
    }

    @Test
    void searchWithQueryOrdersByRelevanceBeforeUpdatedTieBreaker() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "Epic Games",
                "all",
                null,
                null,
                List.of(),
                List.of(),
                null,
                "all",
                "updated",
                1,
                12);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("AS search_score");
        assertThat(sql.getValue()).contains("ORDER BY search_score DESC, a.updated_at DESC");
        assertThat(sql.getValue()).contains("ORDER BY page.search_score DESC, a.updated_at DESC");
        assertThat(params.getValue()[0]).isEqualTo("epic games");
        assertThat(params.getValue()).contains("epic games%");
    }

    @Test
    void searchWithoutQueryKeepsPlainSortOrder() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "",
                "all",
                null,
                null,
                List.of(),
                List.of(),
                null,
                "all",
                "updated",
                1,
                12);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("search_score");
        assertThat(sql.getValue()).contains("ORDER BY a.updated_at DESC, a.normalized_name ASC");
    }

    @Test
    void reviewFilterExcludesAppsThatAlreadyHaveAValidInstaller() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "",
                "review",
                null,
                null,
                List.of(),
                List.of(),
                null,
                "all",
                "updated",
                1,
                12);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("ds.resolution_status = 'requires_manual_review'");
        assertThat(sql.getValue()).contains("NOT EXISTS");
        assertThat(sql.getValue()).contains("valid_source.resolution_status IN ('direct', 'fallback')");
    }

    @Test
    void availableFilterKeepsRecentStaleCandidateForMandatoryWorkerRevalidation() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "", "available", List.of("windows"), null, List.of(), List.of(),
                null, "all", "updated", 1, 12);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("a.operating_systems_json");
        assertThat(sql.getValue()).contains("JSON_CONTAINS");
        assertThat(sql.getValue()).contains("verified_artifact.checked_at >= ?");
        assertThat(sql.getValue()).doesNotContain("verified_artifact.expires_at > NOW()");
        assertThat(params.getValue()).anyMatch(Timestamp.class::isInstance);
    }

    @Test
    void normalizeSearchQueryRemovesAccentsAndCollapsesWhitespace() {
        assertThat(CatalogRepository.normalizeSearchQuery("  Épic   GAMES  "))
                .isEqualTo("epic games");
    }

    private static CatalogRepository repository(JdbcTemplate jdbc) {
        return new CatalogRepository(jdbc, Clock.systemUTC(), Duration.ofDays(7));
    }
}

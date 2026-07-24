package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.BadRequestException;
import java.sql.ResultSet;
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
    void searchWithQueryKeepsReviewAppsAfterVerifiedResults() {
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
        assertThat(sql.getValue()).contains("CASE WHEN a.catalog_status = 'review'");
        assertThat(sql.getValue()).contains("ORDER BY CASE WHEN a.catalog_status");
        assertThat(sql.getValue()).contains("END ASC, search_score DESC, a.updated_at DESC");
        assertThat(sql.getValue()).contains("END ASC, page.search_score DESC, a.updated_at DESC");
        assertThat(params.getValue()[0]).isEqualTo("epic games");
        assertThat(params.getValue()).contains("epic games%");
    }

    @Test
    void hybridSearchBuildsOneJsonCandidateScopeAndRrfBeforeStructuredFilters() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);
        HybridCandidateSet candidates = new HybridCandidateSet(
                CatalogSearchMode.HYBRID,
                CatalogSearchMode.HYBRID,
                "[{\"appId\":\"00000000-0000-0000-0000-000000000001\",\"rank\":1,\"similarity\":0.9}]",
                "model-v1",
                "index-v1",
                null,
                1.25);

        repository.search(
                "editor de código",
                "available",
                List.of("windows"),
                "x86_64",
                List.of("desarrollo"),
                List.of("Vendor"),
                1,
                "all",
                "updated",
                1,
                12,
                candidates);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue())
                .contains("JSON_TABLE", "ROW_NUMBER() OVER", "hybrid_candidates", "rrf_score")
                .contains("1.0 / (60 + lexical.lexical_rank)")
                .contains("? / (60 + semantic.semantic_rank)")
                .contains("a.catalog_status = ?", "JSON_CONTAINS");
        assertThat(params.getValue()[0]).isEqualTo(candidates.candidatesJson());
        assertThat(params.getValue()).contains(1.25, "available", "windows", "x86_64");
    }

    @Test
    void searchWithoutQueryKeepsReviewAppsAfterBothPlainSortOrders() {
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
        assertThat(sql.getValue()).contains("ORDER BY CASE WHEN a.catalog_status");
        assertThat(sql.getValue()).contains("END ASC, a.updated_at DESC, a.normalized_name ASC");

        repository.search(
                "",
                "all",
                null,
                null,
                List.of(),
                List.of(),
                null,
                "all",
                "name",
                1,
                12);

        ArgumentCaptor<String> sortedSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(sortedSql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sortedSql.getAllValues().get(1)).contains("END ASC, a.normalized_name ASC, a.id ASC");
    }

    @Test
    void reviewFilterUsesThePersistentExclusiveProjection() {
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
        assertThat(sql.getValue()).contains("a.catalog_status = ?");
        assertThat(sql.getValue()).doesNotContain("download_sources ds", "resolved_sources");
    }

    @Test
    void pendingFilterIsRejectedAsAnInvalidPublicStatus() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        CatalogRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.search(
                "", "pending", null, null, List.of(), List.of(), null, "all", "updated", 1, 12))
                .isInstanceOf(BadRequestException.class)
                .extracting(exception -> ((BadRequestException) exception).code())
                .isEqualTo("invalid_catalog_status");
    }

    @Test
    void availableFilterUsesProjectionWithoutTemporalCutoff() {
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
        assertThat(sql.getValue()).contains("a.catalog_status = ?");
        assertThat(sql.getValue()).doesNotContain("checked_at >=", "expires_at", "resolved_sources");
        assertThat(params.getValue()).contains("available");
    }

    @Test
    void statsReadsTheSingletonProjectionByPrimaryKey() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getLong("total_count")).thenReturn(10L);
            when(rs.getLong("available_count")).thenReturn(4L);
            when(rs.getLong("review_count")).thenReturn(2L);
            when(rs.getLong("missing_count")).thenReturn(4L);
            return mapper.mapRow(rs, 0);
        });
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        assertThat(repository.stats().filters()).containsExactly(
                org.assertj.core.data.MapEntry.entry("all", 10L),
                org.assertj.core.data.MapEntry.entry("available", 4L),
                org.assertj.core.data.MapEntry.entry("review", 2L),
                org.assertj.core.data.MapEntry.entry("missing", 4L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("FROM catalog_counters", "WHERE id = ?");
        assertThat(sql.getValue()).doesNotContain("COUNT(", "SUM(", " JOIN ", "software_apps", "pending");
        assertThat(params.getValue()).containsExactly(1);
    }

    @Test
    void changeVersionCombinesApplicationProjectionAndScrapeTokens() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                argThat(sql -> sql.contains("MAX(updated_at)")),
                eq(String.class))).thenReturn("apps");
        when(jdbc.queryForObject(
                argThat(sql -> sql.contains("FROM catalog_counters")),
                eq(String.class),
                any(Object[].class))).thenReturn("catalog");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("run"));
        CatalogRepository repository = repository(jdbc);

        assertThat(repository.changeVersion())
                .isEqualTo(Integer.toHexString("apps|catalog|run".hashCode()));

        verify(jdbc).queryForObject(
                argThat(sql -> sql.contains("COUNT(*)") && sql.contains("MAX(updated_at)")),
                eq(String.class));
        verify(jdbc).queryForObject(
                argThat(sql -> sql.contains("FROM catalog_counters") && sql.contains("WHERE id = ?")),
                eq(String.class),
                any(Object[].class));
    }

    @Test
    void normalizeSearchQueryRemovesAccentsAndCollapsesWhitespace() {
        assertThat(CatalogRepository.normalizeSearchQuery("  Épic   GAMES  "))
                .isEqualTo("epic games");
    }

    private static CatalogRepository repository(JdbcTemplate jdbc) {
        return new CatalogRepository(jdbc);
    }
}

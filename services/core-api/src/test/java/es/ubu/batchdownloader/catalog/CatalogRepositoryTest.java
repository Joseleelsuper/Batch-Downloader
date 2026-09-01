package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.BadRequestException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Agrupa los escenarios de prueba de {@code CatalogRepositoryTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class CatalogRepositoryTest {
    /** Comprueba que el enriquecimiento de cualquier conjunto usa cuatro consultas por lote. */
    @Test
    void listItemsUsesFourQueriesRegardlessOfRequestedCardinality() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID appId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet row = org.mockito.Mockito.mock(ResultSet.class);
                    when(row.getBytes("id"))
                            .thenReturn(es.ubu.batchdownloader.common.UuidBytes.fromUuid(appId));
                    when(row.getString("slug")).thenReturn("example");
                    when(row.getString("name")).thenReturn("Example");
                    when(row.getString("catalog_status")).thenReturn("available");
                    when(row.getTimestamp("updated_at"))
                            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 5, 0, 0)));
                    return List.of(mapper.mapRow(row, 0));
                });
        doAnswer(invocation -> null)
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        CatalogRepository repository = repository(jdbc);

        assertThat(repository.listItems(List.of(appId))).containsKey(appId);

        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbc, times(3)).query(
                anyString(), any(RowCallbackHandler.class), any(Object[].class));
    }

    /**
     * Comprueba el escenario {@code manualAppsExposeTheirSourcePageInsteadOfAFakeWinstallUrl}.
     */
    @Test
    void manualAppsExposeTheirSourcePageInsteadOfAFakeWinstallUrl() {
        assertThat(CatalogRepository.originUrl(
                        "manual.example-app",
                        "https://example.com",
                        "https://example.com/download"))
                .isEqualTo("https://example.com/download");
        assertThat(CatalogRepository.originUrl(
                        "manual.example-app",
                        "https://example.com",
                        null))
                .isEqualTo("https://example.com");
        assertThat(CatalogRepository.originUrl(
                        "Valve.Steam",
                        "https://store.steampowered.com/about/",
                        "https://cdn.example.com/steam"))
                .isEqualTo("https://winstall.app/apps/Valve.Steam");
    }

    /**
     * Comprueba el escenario {@code facetLetterGroupsLatinLettersAndNonLatinPrefixes}.
     */
    @Test
    void facetLetterGroupsLatinLettersAndNonLatinPrefixes() {
        assertThat(CatalogRepository.facetLetter(".NET")).isEqualTo("N");
        assertThat(CatalogRepository.facetLetter("Álvaro Tools")).isEqualTo("A");
        assertThat(CatalogRepository.facetLetter("4t Niagara Software")).isEqualTo("#");
        assertThat(CatalogRepository.facetLetter("東Vendor")).isEqualTo("#");
    }

    /**
     * Comprueba el escenario {@code searchWithQueryAppliesSelectedSortBeforeLiteralRelevance}.
     */
    @Test
    void searchWithQueryAppliesSelectedSortBeforeLiteralRelevance() {
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
                "updated",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("AS search_score");
        assertThat(sql.getValue()).contains(
                "ORDER BY a.catalog_review_priority ASC, a.updated_at DESC, "
                        + "search_score DESC, a.normalized_name ASC");
        assertThat(sql.getValue()).doesNotContain(
                "CASE WHEN a.catalog_status", "SELECT a.*", "JOIN (", "page.search_score");
        assertThat(params.getValue()[0]).isEqualTo("epic games");
        assertThat(params.getValue()).contains("epic games%");
    }

    /**
     * Comprueba el escenario {@code
     * semanticSearchUsesOnlyEmbeddingCandidatesBeforeStructuredFilters}.
     */
    @Test
    void semanticSearchUsesOnlyEmbeddingCandidatesBeforeStructuredFilters() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);
        SemanticCandidateSet candidates = new SemanticCandidateSet(
                CatalogSearchMode.SEMANTIC,
                CatalogSearchMode.SEMANTIC,
                "[{\"appId\":\"00000000-0000-0000-0000-000000000001\",\"rank\":1,\"similarity\":0.9}]",
                "model-v1",
                "index-v1",
                null);

        repository.search(
                "editor de código",
                "available",
                List.of("windows"),
                "x86_64",
                List.of("desarrollo"),
                List.of("Vendor"),
                "updated",
                1,
                12,
                candidates);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue())
                .contains("JSON_TABLE", "semantic_candidates", "semantic_rank")
                .doesNotContain("lexical_ranked", "search_score", "rrf")
                .contains("a.catalog_status = ?", "JSON_CONTAINS")
                .contains(
                        "a.catalog_review_priority ASC, a.updated_at DESC, "
                                + "ranked.semantic_rank ASC, a.normalized_name ASC")
                .doesNotContain("SELECT a.*", "JOIN (", "page.semantic_rank");
        assertThat(params.getValue()[0]).isEqualTo(candidates.candidatesJson());
        assertThat(params.getValue()).contains("available", "windows", "x86_64");
    }

    /**
     * Comprueba que las facetas literales aplican conjuntamente tags y editor.
     */
    @Test
    void lexicalFacetsApplyTagsAndPublisherToBothLists() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.facets(
                "editor",
                "available",
                List.of("windows"),
                "x64",
                List.of("automation", "cli"),
                List.of("ACME"),
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .query(sql.capture(), any(RowMapper.class), params.capture());

        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement)
                .contains("LOWER(TRIM(COALESCE(a.publisher, ''))) IN")
                .contains("SELECT COUNT(DISTINCT t.normalized_tag)")
                .contains(">= ?"));
        assertThat(params.getAllValues()).allSatisfy(arguments -> assertThat(arguments)
                .contains("acme", "automation", "cli", 2));
    }

    /**
     * Comprueba que las facetas semánticas usan los mismos filtros estructurados.
     */
    @Test
    void semanticFacetsApplyTagsAndPublisherToBothLists() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);
        SemanticCandidateSet candidates = new SemanticCandidateSet(
                CatalogSearchMode.SEMANTIC,
                CatalogSearchMode.SEMANTIC,
                "[{\"appId\":\"00000000-0000-0000-0000-000000000001\",\"rank\":1}]",
                "model-v1",
                "index-v1",
                null);

        repository.facets(
                "automatización",
                "all",
                List.of(),
                null,
                List.of("automation"),
                List.of("ACME"),
                candidates);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement)
                .contains("semantic_candidates")
                .contains("LOWER(TRIM(COALESCE(a.publisher, ''))) IN")
                .contains("SELECT COUNT(DISTINCT t.normalized_tag)"));
    }

    /**
     * Comprueba el escenario {@code searchWithoutQueryKeepsReviewAppsAfterBothPlainSortOrders}.
     */
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
                "updated",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("search_score");
        assertThat(sql.getValue()).contains(
                "ORDER BY a.catalog_review_priority ASC, a.updated_at DESC, "
                        + "a.normalized_name ASC");
        assertThat(sql.getValue()).doesNotContain(
                "CASE WHEN a.catalog_status", "SELECT a.*", "JOIN (");

        repository.search(
                "",
                "all",
                null,
                null,
                List.of(),
                List.of(),
                "name",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sortedSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(sortedSql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sortedSql.getAllValues().get(1))
                .contains("ORDER BY a.normalized_name ASC, a.id ASC")
                .doesNotContain("catalog_review_priority", "CASE WHEN a.catalog_status");
    }

    /** Comprueba que el índice alfabético calcula páginas sin ordenar filas. */
    @Test
    void alphabetUsesFilteredAggregatePositionsWithoutSorting() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<List<CatalogDtos.CatalogAlphabetEntry>> mapper = invocation.getArgument(1);
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getLong(anyString())).thenAnswer(column -> switch ((String) column.getArgument(0)) {
                case "count_other" -> 2L;
                case "count_a" -> 5L;
                case "before_a" -> 2L;
                case "count_b" -> 3L;
                case "before_b" -> 17L;
                default -> 0L;
            });
            return List.of(mapper.mapRow(rs, 0));
        });
        CatalogRepository repository = repository(jdbc);

        assertThat(repository.alphabet(
                        "", "all", null, null, List.of(), List.of(), 12,
                        SemanticCandidateSet.lexical()))
                .containsExactly(
                        new CatalogDtos.CatalogAlphabetEntry("#", 1, 2),
                        new CatalogDtos.CatalogAlphabetEntry("A", 1, 5),
                        new CatalogDtos.CatalogAlphabetEntry("B", 2, 3));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("SUM(a.normalized_name < ?)", "WHERE a.app_status = 'active'")
                .doesNotContain("ORDER BY", "SELECT a.*");
    }

    /**
     * Comprueba el escenario {@code mostDownloadedSortUsesPersistentCompletedDownloadCount}.
     */
    @Test
    void mostDownloadedSortUsesPersistentCompletedDownloadCount() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "",
                "available",
                null,
                null,
                List.of(),
                List.of(),
                "downloads",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("a.download_count DESC, a.normalized_name ASC, a.id ASC")
                .doesNotContain("JOIN download_job_items");
    }

    /**
     * Comprueba el escenario {@code mostDownloadedSortRemainsPrimaryWhenSearching}.
     */
    @Test
    void mostDownloadedSortRemainsPrimaryWhenSearching() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "launcher",
                "available",
                null,
                null,
                List.of(),
                List.of(),
                "downloads",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains(
                "a.catalog_review_priority ASC, a.download_count DESC, "
                        + "search_score DESC, a.normalized_name ASC");
        assertThat(sql.getValue()).doesNotContain("SELECT a.*", "JOIN (", "page.search_score");
    }

    /**
     * Comprueba el escenario {@code reviewFilterUsesThePersistentExclusiveProjection}.
     */
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
                "updated",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue()).contains("a.catalog_status = ?");
        assertThat(sql.getValue()).doesNotContain("download_sources ds", "resolved_sources");
    }

    /**
     * Comprueba el escenario {@code unresolvedFilterUsesTheTwoPersistentAdministrativeStates}.
     */
    @Test
    void unresolvedFilterUsesTheTwoPersistentAdministrativeStates() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "",
                "unresolved",
                null,
                null,
                List.of(),
                List.of(),
                "updated",
                1,
                12,
                SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue())
                .contains("a.catalog_status IN ('review', 'missing')")
                .doesNotContain("a.catalog_status = ?");
        assertThat(params.getValue()).doesNotContain("unresolved");
        assertThat(CatalogRepository.normalizeCatalogStatus("unresolved"))
                .isEqualTo("unresolved");
    }

    /**
     * Comprueba el escenario {@code pendingFilterIsRejectedAsAnInvalidPublicStatus}.
     */
    @Test
    void pendingFilterIsRejectedAsAnInvalidPublicStatus() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        CatalogRepository repository = repository(jdbc);

        assertThatThrownBy(() -> repository.search(
                "", "pending", null, null, List.of(), List.of(), "updated", 1, 12,
                SemanticCandidateSet.lexical()))
                .isInstanceOf(BadRequestException.class)
                .extracting(exception -> ((BadRequestException) exception).code())
                .isEqualTo("invalid_catalog_status");
    }

    /**
     * Comprueba el escenario {@code availableFilterUsesProjectionWithoutTemporalCutoff}.
     */
    @Test
    void availableFilterUsesProjectionWithoutTemporalCutoff() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        repository.search(
                "", "available", List.of("windows"), null, List.of(), List.of(),
                "updated", 1, 12, SemanticCandidateSet.lexical());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), params.capture());
        assertThat(sql.getValue()).contains("a.operating_systems_json");
        assertThat(sql.getValue()).contains("JSON_CONTAINS");
        assertThat(sql.getValue()).contains("a.catalog_status = ?");
        assertThat(sql.getValue()).doesNotContain("checked_at >=", "expires_at", "resolved_sources");
        assertThat(params.getValue()).contains("available");
    }

    /**
     * Comprueba el escenario {@code statsReadsTheSingletonProjectionByPrimaryKey}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void statsReadsTheSingletonProjectionByPrimaryKey() throws Exception {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
            when(rs.getLong("total_apps")).thenReturn(10L);
            when(rs.getLong("available_apps")).thenReturn(4L);
            when(rs.getLong("review_apps")).thenReturn(2L);
            when(rs.getLong("missing_installer_apps")).thenReturn(4L);
            return mapper.mapRow(rs, 0);
        });
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        CatalogRepository repository = repository(jdbc);

        var stats = repository.stats();
        assertThat(stats.filters()).containsExactly(
                org.assertj.core.data.MapEntry.entry("all", 10L),
                org.assertj.core.data.MapEntry.entry("available", 4L),
                org.assertj.core.data.MapEntry.entry("review", 2L),
                org.assertj.core.data.MapEntry.entry("missing", 4L));
        assertThat(stats.generatedAt()).isEqualTo(LocalDateTime.of(2026, 8, 23, 1, 0));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("FROM application_totals");
        assertThat(sql.getValue()).doesNotContain("COUNT(", "SUM(", " JOIN ", "software_apps", "pending");
    }

    /**
     * Comprueba el escenario {@code changeVersionCombinesApplicationProjectionAndScrapeTokens}.
     */
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

    /**
     * Comprueba el escenario {@code normalizeSearchQueryRemovesAccentsAndCollapsesWhitespace}.
     */
    @Test
    void normalizeSearchQueryRemovesAccentsAndCollapsesWhitespace() {
        assertThat(CatalogRepository.normalizeSearchQuery("  Épic   GAMES  "))
                .isEqualTo("epic games");
    }

    /**
     * Ejecuta la operación {@code repository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @return Resultado producido por {@code repository}.
     */
    private static CatalogRepository repository(JdbcTemplate jdbc) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-23T01:00:00Z"), ZoneOffset.UTC);
        return new CatalogRepository(
                jdbc,
                new CatalogStatisticsRepository(jdbc, clock),
                new CatalogProjectionRepository(jdbc),
                new CatalogFacetRepository(jdbc));
    }
}

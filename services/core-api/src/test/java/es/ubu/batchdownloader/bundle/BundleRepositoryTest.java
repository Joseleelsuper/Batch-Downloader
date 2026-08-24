package es.ubu.batchdownloader.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

/**
 * Agrupa los escenarios de prueba de {@code BundleRepositoryTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class BundleRepositoryTest {
    /**
     * Comprueba el presupuesto constante: cuatro consultas propias y cuatro del catálogo por
     * página, nunca consultas dentro del RowMapper.
     */
    @Test
    void bundlePageStaysWithinEightQueries() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CatalogRepository catalog = mock(CatalogRepository.class);
        UUID bundleId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    java.sql.ResultSet row = mock(java.sql.ResultSet.class);
                    when(row.getBytes("id"))
                            .thenReturn(es.ubu.batchdownloader.common.UuidBytes.fromUuid(bundleId));
                    when(row.getString("slug")).thenReturn("bundle");
                    when(row.getString("name")).thenReturn("Bundle");
                    when(row.getString("type")).thenReturn("official");
                    when(row.getString("visibility")).thenReturn("public");
                    when(row.getTimestamp("updated_at"))
                            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 8, 5, 0, 0)));
                    return List.of(mapper.mapRow(row, 0));
                });
        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("FROM bundle_items")) {
                        RowCallbackHandler handler = invocation.getArgument(1);
                        java.sql.ResultSet row = mock(java.sql.ResultSet.class);
                        when(row.getBytes("bundle_id"))
                                .thenReturn(es.ubu.batchdownloader.common.UuidBytes.fromUuid(bundleId));
                        when(row.getBytes("software_app_id"))
                                .thenReturn(es.ubu.batchdownloader.common.UuidBytes.fromUuid(appId));
                        when(row.getString("operating_system")).thenReturn("windows");
                        handler.processRow(row);
                    }
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        when(catalog.listItems(any())).thenReturn(Map.of());
        BundleRepository repository = repository(jdbc, catalog);

        assertThat(repository.list(null, "updated", 1, 12)).hasSize(1);
        assertThat(repository.count(null)).isEqualTo(1);

        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verify(jdbc, times(2)).query(
                anyString(), any(RowCallbackHandler.class), any(Object[].class));
        verify(jdbc, times(1)).queryForObject(
                anyString(), eq(Long.class), any(Object[].class));
        verify(catalog).listItems(any());
    }

    /**
     * Comprueba el escenario {@code writesAuthenticatedBundleOwnerAsTextUuid}.
     */
    @Test
    void writesAuthenticatedBundleOwnerAsTextUuid() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID ownerId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        doThrow(new RuntimeException("stop after bundle insert"))
                .when(jdbc)
                .update(anyString(), any(Object[].class));
        BundleRepository repository = repository(
                jdbc, org.mockito.Mockito.mock(CatalogRepository.class));

        assertThatThrownBy(() -> repository.create(
                        new UpsertBundleRequest(
                                "Programas de desarrollo",
                                "Programas de desarrollo.",
                                null,
                                "official",
                                "official",
                                List.of("trabajo"),
                                List.of()),
                        ownerId))
                .hasMessage("stop after bundle insert");

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue()[6]).isEqualTo(ownerId.toString());
    }

    /**
     * Comprueba el escenario {@code exposesPlatformsWithASelectableInstallerRegardlessOfAge}.
     */
    @Test
    void exposesPlatformsWithASelectableInstallerRegardlessOfAge() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID appId = UUID.randomUUID();
        doAnswer(invocation -> {
                    RowCallbackHandler handler = invocation.getArgument(1);
                    java.sql.ResultSet row = mock(java.sql.ResultSet.class);
                    when(row.getString("operating_system")).thenReturn("windows");
                    when(row.getBytes("software_app_id"))
                            .thenReturn(es.ubu.batchdownloader.common.UuidBytes.fromUuid(appId));
                    handler.processRow(row);
                    return null;
                })
                .when(jdbc)
                .query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        BundleRepository repository = repository(jdbc, mock(CatalogRepository.class));

        assertThat(repository.availableOperatingSystems(UUID.randomUUID())).containsExactly("windows");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), parameters.capture());
        assertThat(sql.getValue()).contains("source.catalog_available = 1");
        assertThat(sql.getValue()).contains("artifact.catalog_downloadable = 1");
        assertThat(sql.getValue()).contains("app.app_status = 'active'");
        assertThat(sql.getValue()).contains("app.catalog_status = 'available'");
        assertThat(sql.getValue()).doesNotContain("artifact.checked_at >= ?", "artifact.expires_at > NOW()");
        assertThat(sql.getValue()).contains("SELECT source.operating_system, app.id AS software_app_id");
        assertThat(sql.getValue()).contains("GROUP BY source.operating_system, app.id");
        assertThat(sql.getValue()).doesNotContain("HAVING", "expected_item");
        assertThat(sql.getValue()).contains("FIELD(source.operating_system, 'windows', 'linux', 'macos')");
        assertThat(parameters.getValue()).hasSize(1);
    }

    /** Comprueba que una descarga de bundle solo materializa acceso e identificadores acotados. */
    @Test
    void loadsAtMostOneHundredAndOneIdsForBundleDownload() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID bundleId = UUID.randomUUID();
        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<?> mapper = invocation.getArgument(1);
                    if (!sql.contains("FROM bundles")) {
                        return List.of();
                    }
                    java.sql.ResultSet row = mock(java.sql.ResultSet.class);
                    when(row.getBytes("id"))
                            .thenReturn(es.ubu.batchdownloader.common.UuidBytes.fromUuid(bundleId));
                    when(row.getString("visibility")).thenReturn("public");
                    return List.of(mapper.mapRow(row, 0));
                })
                .when(jdbc)
                .query(anyString(), any(RowMapper.class), any(Object[].class));
        BundleRepository repository = repository(jdbc, mock(CatalogRepository.class));

        assertThat(repository.appIdsForDownload("public-bundle", null, false)).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        String itemQuery = sql.getAllValues().stream()
                .filter(value -> value.contains("FROM bundle_items"))
                .findFirst()
                .orElseThrow();
        assertThat(itemQuery)
                .contains("SELECT item.software_app_id")
                .contains("LIMIT 101")
                .doesNotContain("SELECT *");
    }

    private static BundleRepository repository(JdbcTemplate jdbc, CatalogRepository catalog) {
        BundleReadRepository reads = new BundleReadRepository(jdbc, catalog);
        BundleWriteRepository writes = new BundleWriteRepository(jdbc, catalog, reads);
        return new BundleRepository(reads, writes);
    }
}

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
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import java.util.List;
import java.util.UUID;
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
     * Comprueba el escenario {@code writesAuthenticatedBundleOwnerAsTextUuid}.
     */
    @Test
    void writesAuthenticatedBundleOwnerAsTextUuid() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        UUID ownerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(ownerId));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        doThrow(new RuntimeException("stop after bundle insert"))
                .when(jdbc)
                .update(anyString(), any(Object[].class));
        BundleRepository repository = new BundleRepository(
                jdbc,
                org.mockito.Mockito.mock(CatalogRepository.class));

        assertThatThrownBy(() -> repository.create(
                        new UpsertBundleRequest(
                                "Programas de desarrollo",
                                "Programas de desarrollo.",
                                null,
                                "official",
                                "official",
                                List.of("trabajo"),
                                List.of()),
                        "admin"))
                .hasMessage("stop after bundle insert");

        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), parameters.capture());
        assertThat(parameters.getValue()[7]).isEqualTo(ownerId.toString());
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
        BundleRepository repository = new BundleRepository(
                jdbc,
                mock(CatalogRepository.class));

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
}

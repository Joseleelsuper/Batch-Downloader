package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import es.ubu.batchdownloader.common.UuidBytes;
import es.ubu.batchdownloader.downloads.application.LinuxTarget;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * Agrupa los escenarios de prueba de {@code JpaCatalogSourceLookupTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class JpaCatalogSourceLookupTest {
    @Test
    void previewMetadataMapsNullableSizesAndBindsOnlyRequestedSourceIds() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaCatalogSourceLookup lookup = new JpaCatalogSourceLookup(jdbc);
        assertThat(lookup.sourceMetadata(List.of())).isEmpty();
        assertThat(lookup.appNames(List.of())).isEmpty();
        verifyNoInteractions(jdbc);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        ResultSet known = mock(ResultSet.class), unknown = mock(ResultSet.class);
        when(known.getBytes("id")).thenReturn(UuidBytes.fromUuid(first));
        when(known.getString("final_domain")).thenReturn("example.com");
        when(known.getString("filename")).thenReturn("example.exe");
        when(known.getObject("size_bytes")).thenReturn(4096L);
        when(unknown.getBytes("id")).thenReturn(UuidBytes.fromUuid(second));
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(known);
            handler.processRow(unknown);
            return null;
        }).when(jdbc).query(any(String.class), any(RowCallbackHandler.class), any(Object[].class));

        var metadata = lookup.sourceMetadata(List.of(first, second));
        assertThat(metadata.get(first).sizeBytes()).isEqualTo(4096L);
        assertThat(metadata.get(first).filename()).isEqualTo("example.exe");
        assertThat(metadata.get(first).finalDomain()).isEqualTo("example.com");
        assertThat(metadata.get(second).sizeBytes()).isNull();
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(any(String.class), any(RowCallbackHandler.class), parameters.capture());
        assertThat(parameters.getValue()).containsExactly(UuidBytes.fromUuid(first), UuidBytes.fromUuid(second));
    }


    /** Comprueba que revisión y ausencia puedan usar la página oficial como alternativa. */
    @Test
    void selectsOfficialPagesWithoutRequiringCatalogAvailability() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaCatalogSourceLookup lookup = new JpaCatalogSourceLookup(jdbc);

        assertThat(lookup.findManualSources(List.of(UUID.randomUUID()))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), any(Object[].class));
        assertThat(sql.getValue()).contains("app_status = 'active'");
        assertThat(sql.getValue()).contains("official_url IS NOT NULL");
        assertThat(sql.getValue()).doesNotContain("catalog_status = 'available'");
        assertThat(sql.getValue()).contains("LIMIT 101");
    }

    /**
     * Comprueba el escenario {@code selectsStaleValidSourcesAndOrdersPlatformsCanonically}.
     */
    @Test
    void selectsStaleValidSourcesAndOrdersPlatformsCanonically() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaCatalogSourceLookup lookup = new JpaCatalogSourceLookup(jdbc);

        assertThat(lookup.findVerifiedSources(List.of(UUID.randomUUID()), List.of("linux", "windows")))
                .isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), parameters.capture());
        assertThat(sql.getValue()).contains("ds.catalog_available = 1");
        assertThat(sql.getValue()).contains("rs.catalog_downloadable = 1");
        assertThat(sql.getValue()).contains("app.app_status = 'active'");
        assertThat(sql.getValue()).contains("app.catalog_status = 'available'");
        assertThat(sql.getValue()).doesNotContain("rs.checked_at >= ?", "rs.expires_at > NOW()");
        assertThat(sql.getValue()).doesNotContain("validation_confidence");
        assertThat(sql.getValue()).doesNotContain("transport_security");
        assertThat(sql.getValue()).contains("FIELD(ds.operating_system, 'windows', 'linux', 'macos') ASC");
        assertThat(sql.getValue()).contains("rs.id ASC");
        assertThat(sql.getValue()).contains("ROW_NUMBER() OVER (");
        assertThat(sql.getValue()).contains("WHERE source_rank = 1");
        assertThat(sql.getValue()).contains("LIMIT 101");
        assertThat(Arrays.stream(parameters.getValue())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList()).containsExactly("windows", "linux");
    }

    /** Comprueba que una fuente concreta se limite a su aplicación y siga validándose. */
    @Test
    void selectsAnExplicitSourceOnlyWhenItRemainsCatalogDownloadable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaCatalogSourceLookup lookup = new JpaCatalogSourceLookup(jdbc);

        assertThat(lookup.findVerifiedSource(
                UUID.randomUUID(), UUID.randomUUID(), List.of("windows"))).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), parameters.capture());
        assertThat(sql.getValue()).contains("ds.software_app_id = ?");
        assertThat(sql.getValue()).contains("rs.id = ?");
        assertThat(sql.getValue()).contains("ds.validation_status = 'valid'");
        assertThat(sql.getValue()).contains("ds.catalog_available = 1");
        assertThat(sql.getValue()).contains("rs.catalog_downloadable = 1");
        assertThat(sql.getValue()).contains("LIMIT 1");
        assertThat(parameters.getValue()).hasSize(3);
        assertThat(parameters.getValue()[2]).isEqualTo("windows");
    }

    @Test
    void constrainsLinuxSelectionByManagerArchitectureRecipeAndExactSource() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JpaCatalogSourceLookup lookup = new JpaCatalogSourceLookup(jdbc);

        assertThat(lookup.findLinuxSources(
                List.of(UUID.randomUUID()), new LinuxTarget("pacman", "aarch64"), UUID.randomUUID()))
                .isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowCallbackHandler.class), parameters.capture());
        assertThat(sql.getValue())
                .contains("ds.operating_system = 'linux'")
                .contains("LOWER(rs.extension) IN")
                .contains("ds.architecture IN (?, 'any', 'all', 'noarch', 'universal', 'unknown')")
                .contains("lip.status = 'approved'")
                .contains("JSON_CONTAINS")
                .contains("rs.id = ?")
                .contains("'.pkg.tar.zst'");
        assertThat(Arrays.stream(parameters.getValue())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList())
                .containsExactly(
                        ".pkg.tar.zst", ".appimage", ".tar.gz", ".jar",
                        "aarch64", "pacman", "aarch64");
    }
}

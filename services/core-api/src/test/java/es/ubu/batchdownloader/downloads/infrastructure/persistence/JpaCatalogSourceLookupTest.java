package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

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

    /**
     * Comprueba el escenario {@code selectsStaleValidSourcesAndOrdersPlatformsCanonically}.
     */
    @Test
    void selectsStaleValidSourcesAndOrdersPlatformsCanonically() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
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
        assertThat(Arrays.stream(parameters.getValue())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList()).containsExactly("windows", "linux");
    }
}

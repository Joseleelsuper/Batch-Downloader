package es.ubu.batchdownloader.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.catalog.CatalogRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BundleRepositoryTest {
    @Test
    void exposesPlatformsWithASelectableInstallerRegardlessOfAge() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("windows"));
        BundleRepository repository = new BundleRepository(
                jdbc,
                org.mockito.Mockito.mock(CatalogRepository.class));

        assertThat(repository.availableOperatingSystems(UUID.randomUUID())).containsExactly("windows");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), parameters.capture());
        assertThat(sql.getValue()).contains("source.catalog_available = 1");
        assertThat(sql.getValue()).contains("artifact.catalog_downloadable = 1");
        assertThat(sql.getValue()).contains("app.app_status = 'active'");
        assertThat(sql.getValue()).contains("app.catalog_status = 'available'");
        assertThat(sql.getValue()).doesNotContain("artifact.checked_at >= ?", "artifact.expires_at > NOW()");
        assertThat(sql.getValue()).contains("SELECT DISTINCT source.operating_system");
        assertThat(sql.getValue()).doesNotContain("HAVING", "expected_item");
        assertThat(sql.getValue()).contains("FIELD(source.operating_system, 'windows', 'linux', 'macos')");
        assertThat(parameters.getValue()).hasSize(1);
    }
}

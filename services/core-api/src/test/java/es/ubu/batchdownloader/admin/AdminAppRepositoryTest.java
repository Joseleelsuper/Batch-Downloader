package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.FernetUrlProtector;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AdminAppRepositoryTest {
    @Test
    void deleteAllRejectsDeletionWhileScraperIsRunning() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", new byte[16])));
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class),
                "test-secret");

        assertThatThrownBy(repository::deleteAll)
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("scraper_running");
    }

    @Test
    void deleteAllClearsPersistedPipelineStateForAnEmptyCatalog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class)).thenReturn(0);
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class),
                "test-secret");

        assertThat(repository.deleteAll()).isZero();

        verify(jdbc).update("DELETE FROM scraper_worker_snapshots");
        verify(jdbc).update("DELETE FROM scraper_metric_snapshots");
        verify(jdbc).update("DELETE FROM scraper_work_items");
    }

    @Test
    void exportCsvUsesBestUrlsPerPlatformAndNoneForMissingValues() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FernetUrlProtector protector = new FernetUrlProtector("test-secret");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(
                    mapper.mapRow(row(
                            "1",
                            "Steam",
                            "Valve.Steam",
                            "https://store.steampowered.com/about/",
                            "windows",
                            ".exe",
                            protector.protect("https://cdn.example.com/SteamSetup.exe")),
                            0),
                    mapper.mapRow(row(
                            "1",
                            "Steam",
                            "Valve.Steam",
                            "https://store.steampowered.com/about/",
                            "linux",
                            ".deb",
                            protector.protect("https://cdn.example.com/steam.deb")),
                            1),
                    mapper.mapRow(row(
                            "2",
                            "Comma, App",
                            "manual.comma-app",
                            null,
                            "windows",
                            ".msi",
                            protector.protect("https://cdn.example.com/comma.msi")),
                            2));
        });
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class),
                "test-secret");

        AdminAppRepository.AppCsvExport export = repository.exportCsv();

        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(export.content()).startsWith("Nombre,Winstall,URL,Windows,Linux,MacOS\r\n");
        assertThat(export.content()).contains(
                "Steam,https://winstall.app/apps/Valve.Steam,https://store.steampowered.com/about/,https://cdn.example.com/SteamSetup.exe,https://cdn.example.com/steam.deb,None\r\n");
        assertThat(export.content()).contains(
                "\"Comma, App\",None,None,https://cdn.example.com/comma.msi,None,None\r\n");
    }

    private ResultSet row(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String operatingSystem,
            String extension,
            String encryptedUrl) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("app_key")).thenReturn(appKey);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getString("winstall_id")).thenReturn(winstallId);
        when(rs.getString("official_url")).thenReturn(officialUrl);
        when(rs.getString("operating_system")).thenReturn(operatingSystem);
        when(rs.getString("extension")).thenReturn(extension);
        when(rs.getString("resolved_url_encrypted")).thenReturn(encryptedUrl);
        return rs;
    }
}

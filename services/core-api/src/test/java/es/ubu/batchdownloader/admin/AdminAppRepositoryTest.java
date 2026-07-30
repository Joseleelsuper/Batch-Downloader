package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Agrupa los escenarios de prueba de {@code AdminAppRepositoryTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class AdminAppRepositoryTest {
    /**
     * Comprueba el escenario {@code deleteAllRejectsDeletionWhileScraperIsRunning}.
     */
    @Test
    void deleteAllRejectsDeletionWhileScraperIsRunning() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", new byte[16])));
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class));

        assertThatThrownBy(repository::deleteAll)
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("scraper_running");
    }

    /**
     * Comprueba el escenario {@code deleteAllClearsPersistedPipelineStateForAnEmptyCatalog}.
     */
    @Test
    void deleteAllClearsPersistedPipelineStateForAnEmptyCatalog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class)).thenReturn(0);
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class));

        assertThat(repository.deleteAll()).isZero();

        verify(jdbc).update("DELETE FROM scraper_worker_snapshots");
        verify(jdbc).update("DELETE FROM scraper_metric_snapshots");
        verify(jdbc).update("DELETE FROM scraper_work_items");
    }

    /**
     * Comprueba el escenario {@code
     * deleteAllUsesPrimaryKeyBatchesInsteadOfTriggerConflictingSubqueries}.
     */
    @Test
    void deleteAllUsesPrimaryKeyBatchesInsteadOfTriggerConflictingSubqueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class)).thenReturn(1);
        byte[] appId = new byte[16];
        byte[] sourceId = new byte[16];
        byte[] resolvedId = new byte[16];
        appId[0] = 1;
        sourceId[0] = 2;
        resolvedId[0] = 3;
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.equals("SELECT id FROM software_apps")) {
                return List.of(appId);
            }
            if (sql.startsWith("SELECT id FROM download_sources")) {
                return List.of(sourceId);
            }
            if (sql.startsWith("SELECT id FROM resolved_sources")) {
                return List.of(resolvedId);
            }
            return List.of();
        });
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class));

        assertThat(repository.deleteAll()).isEqualTo(1);

        List<String> deleteStatements = org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("update"))
                .map(invocation -> invocation.getArgument(0, String.class))
                .filter(sql -> sql.startsWith("DELETE FROM "))
                .toList();
        assertThat(deleteStatements).contains(
                "DELETE FROM resolved_sources WHERE id IN (?)",
                "DELETE FROM download_sources WHERE id IN (?)",
                "DELETE FROM software_apps WHERE id IN (?)");
        assertThat(deleteStatements).allMatch(sql -> !sql.contains("SELECT"));
    }

    /**
     * Comprueba el escenario {@code exportCsvUsesSourceRefsAndNeverRevealsResolvedUrls}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void exportCsvUsesSourceRefsAndNeverRevealsResolvedUrls() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
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
                            "00000000-0000-0000-0000-000000000001"),
                            0),
                    mapper.mapRow(row(
                            "1",
                            "Steam",
                            "Valve.Steam",
                            "https://store.steampowered.com/about/",
                            "linux",
                            ".deb",
                            "00000000-0000-0000-0000-000000000002"),
                            1),
                    mapper.mapRow(row(
                            "2",
                            "Comma, App",
                            "manual.comma-app",
                            null,
                            "windows",
                            ".msi",
                            "00000000-0000-0000-0000-000000000003"),
                            2));
        });
        AdminAppRepository repository = new AdminAppRepository(
                jdbc,
                mock(CatalogRepository.class));

        AdminAppRepository.AppCsvExport export = repository.exportCsv();

        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(export.content()).startsWith(
                "Nombre,Winstall,URL,WindowsSourceRef,LinuxSourceRef,MacOSSourceRef\r\n");
        assertThat(export.content()).contains(
                "Steam,https://winstall.app/apps/Valve.Steam,https://store.steampowered.com/about/,00000000-0000-0000-0000-000000000001,00000000-0000-0000-0000-000000000002,None\r\n");
        assertThat(export.content()).contains(
                "\"Comma, App\",None,None,00000000-0000-0000-0000-000000000003,None,None\r\n");
        assertThat(export.content()).doesNotContain("cdn.example.com", "resolved_url_encrypted");
    }

    /**
     * Comprueba el escenario {@code patchSourceScopesTheMutationToItsOwningApplication}.
     */
    @Test
    void patchSourceScopesTheMutationToItsOwningApplication() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CatalogRepository catalog = mock(CatalogRepository.class);
        UUID applicationId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        when(catalog.softwareAppId("app-public-id")).thenReturn(applicationId);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AdminAppRepository repository = new AdminAppRepository(jdbc, catalog);

        repository.patchSource(
                "app-public-id",
                sourceId.toString(),
                new PatchSourceRequest(
                        "windows",
                        "x86_64",
                        null,
                        null,
                        null,
                        null));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains("WHERE id = ? AND software_app_id = ?");
        assertThat((byte[]) parameters.getValue()[7])
                .containsExactly(UuidBytes.fromUuid(sourceId));
        assertThat((byte[]) parameters.getValue()[8])
                .containsExactly(UuidBytes.fromUuid(applicationId));
    }

    /**
     * Ejecuta la operación {@code row}.
     *
     * @param appKey Valor de {@code appKey} utilizado por la operación.
     * @param name Nombre del elemento sobre el que se actúa.
     * @param winstallId Identificador de {@code winstall} utilizado por la operación.
     * @param officialUrl Dirección de {@code official} que debe procesarse.
     * @param operatingSystem Valor de {@code operatingSystem} utilizado por la operación.
     * @param extension Valor de {@code extension} utilizado por la operación.
     * @param sourceRef Valor de {@code sourceRef} utilizado por la operación.
     * @return Resultado producido por {@code row}.
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private ResultSet row(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String operatingSystem,
            String extension,
            String sourceRef) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("app_key")).thenReturn(appKey);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getString("winstall_id")).thenReturn(winstallId);
        when(rs.getString("official_url")).thenReturn(officialUrl);
        when(rs.getString("operating_system")).thenReturn(operatingSystem);
        when(rs.getString("extension")).thenReturn(extension);
        when(rs.getString("source_ref")).thenReturn(sourceRef);
        return rs;
    }
}

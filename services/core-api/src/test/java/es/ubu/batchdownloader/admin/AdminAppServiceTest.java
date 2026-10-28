package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyRequest;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyResult;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyRequest;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyResult;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Conserva publicación remota, lectura del resultado y auditoría con metadatos seguros. */
class AdminAppServiceTest {
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final AdminAppRepository apps = mock(AdminAppRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final ScraperInternalClient scraper = mock(ScraperInternalClient.class);
    private final AdminAppService service = new AdminAppService(catalog, apps, audit, scraper);

    @Test
    void manualPublicationReadsTheCommittedAppAndAuditsOnlyItsIdentifiers() {
        var request = mock(ManualInstallerApplyRequest.class);
        var app = mock(AppDetails.class);
        var result = new ManualInstallerApplyResult("app", "source", List.of("source"), 3,
                "available", List.of("warning"));
        when(scraper.applyManualInstallerInspection("app", "inspection", request)).thenReturn(result);
        when(catalog.details("app")).thenReturn(app);

        var response = service.applyManualInstallerInspection("app", "inspection", request, "actor");

        assertThat(response.application()).isSameAs(app);
        assertThat(response.sourceRefs()).containsExactly("source");
        assertThat(response.warnings()).containsExactly("warning");
        var order = inOrder(scraper, catalog, audit);
        order.verify(scraper).applyManualInstallerInspection("app", "inspection", request);
        order.verify(catalog).details("app");
        order.verify(audit).record("actor", "app.manual_installer.apply", "app", "app", Map.of(
                "inspectionId", "inspection", "sourceRef", "source", "sourceRefs", List.of("source"),
                "catalogStatus", "available"));
    }

    @Test
    void discoveryPublicationKeepsItsResponseAndAuditMetadata() {
        var request = mock(WebsiteAppDiscoveryApplyRequest.class);
        var app = mock(AppDetails.class);
        when(scraper.applyWebsiteAppDiscovery("discovery", request)).thenReturn(
                new WebsiteAppDiscoveryApplyResult("app", 1, "available", 2, List.of()));
        when(catalog.details("app")).thenReturn(app);

        var response = service.applyWebsiteAppDiscovery("discovery", request, "actor");

        assertThat(response.application()).isSameAs(app);
        assertThat(response.installerCount()).isEqualTo(2);
        var order = inOrder(scraper, catalog, audit);
        order.verify(scraper).applyWebsiteAppDiscovery("discovery", request);
        order.verify(catalog).details("app");
        order.verify(audit).record("actor", "app.website_discovery.apply", "app", "app", Map.of(
                "discoveryId", "discovery", "catalogStatus", "available", "installerCount", 2));
    }

    @Test
    void invalidDeleteConfirmationDoesNotWriteOrAudit() {
        assertThatThrownBy(() -> service.deleteAllApps("wrong", "actor"))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(apps, audit);
    }
}

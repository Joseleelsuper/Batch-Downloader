package es.ubu.batchdownloader.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.catalog.SemanticCandidateSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba del contrato de filtrado administrativo.
 */
class AdminAppControllerTest {
    /** Comprueba el contrato administrativo sin los filtros URL retirados. */
    @Test
    void listAppsUsesOnlyCurrentAdministrativeFilters() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.search(
                        eq("editor"),
                        eq("unresolved"),
                        eq(List.of("windows")),
                        eq("x64"),
                        eq(List.of()),
                        eq(List.of()),
                        eq("updated"),
                        eq(1),
                        eq(20),
                        any(SemanticCandidateSet.class)))
                .thenReturn(List.of());
        AdminAppController controller = new AdminAppController(
                catalog,
                mock(AdminAppRepository.class),
                mock(AdminAuditService.class),
                mock(ScraperInternalClient.class));

        controller.listApps(
                "editor",
                "unresolved",
                "windows",
                "x64",
                "updated",
                1,
                20);

        verify(catalog).search(
                eq("editor"),
                eq("unresolved"),
                eq(List.of("windows")),
                eq("x64"),
                eq(List.of()),
                eq(List.of()),
                eq("updated"),
                eq(1),
                eq(20),
                any(SemanticCandidateSet.class));
    }
}

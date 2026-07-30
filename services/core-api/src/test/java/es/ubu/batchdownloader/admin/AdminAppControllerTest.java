package es.ubu.batchdownloader.admin;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.catalog.CatalogRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba del contrato de filtrado administrativo.
 */
class AdminAppControllerTest {
    /**
     * Comprueba que administración conserva los modos de tags {@code any} y {@code all}.
     */
    @Test
    void listAppsPreservesAdministrativeTagMode() {
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.search(
                        eq("editor"),
                        eq("unresolved"),
                        eq(List.of("windows")),
                        eq("x64"),
                        eq(List.of("automation", "cli")),
                        eq(List.of()),
                        eq("any"),
                        eq("updated"),
                        eq(1),
                        eq(20)))
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
                "automation,cli",
                "any",
                "updated",
                1,
                20);
        controller.listApps(
                null,
                "unresolved",
                null,
                null,
                "automation,cli",
                "all",
                "updated",
                1,
                20);

        verify(catalog).search(
                eq("editor"),
                eq("unresolved"),
                eq(List.of("windows")),
                eq("x64"),
                eq(List.of("automation", "cli")),
                eq(List.of()),
                eq("any"),
                eq("updated"),
                eq(1),
                eq(20));
        verify(catalog).search(
                eq(null),
                eq("unresolved"),
                eq(List.of()),
                eq(null),
                eq(List.of("automation", "cli")),
                eq(List.of()),
                eq("all"),
                eq("updated"),
                eq(1),
                eq(20));
    }
}

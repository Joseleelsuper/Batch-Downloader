package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Agrupa los escenarios de prueba de {@code CatalogControllerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {
    /**
     * Dato compartido {@code catalog} para los escenarios de prueba.
     */
    @Mock
    private CatalogRepository catalog;

    /**
     * Comprueba el escenario {@code appsMergesRepeatedAndLegacyTagParamsAndKeepsPublisherCommas}.
     */
    @Test
    void appsMergesRepeatedAndLegacyTagParamsAndKeepsPublisherCommas() {
        when(catalog.search(any(), any(), any(), any(), anyList(), anyList(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        CatalogController controller = new CatalogController(catalog);

        controller.apps(
                "epic",
                "available",
                null,
                null,
                List.of(".NET", "runtime"),
                "Windows,Desktop",
                List.of("ACME, Inc.", "東Vendor"),
                2,
                "all",
                "updated",
                1,
                20);

        verify(catalog).search(
                eq("epic"),
                eq("available"),
                eq(List.of()),
                isNull(),
                eq(List.of(".NET", "runtime", "Windows", "Desktop")),
                eq(List.of("ACME, Inc.", "東Vendor")),
                eq(2),
                eq("all"),
                eq("updated"),
                eq(1),
                eq(20));
    }

    /**
     * Comprueba el escenario {@code facetsParsesTheSameFilterContractAsApps}.
     */
    @Test
    void facetsParsesTheSameFilterContractAsApps() {
        CatalogController controller = new CatalogController(catalog);

        controller.facets(
                null,
                "review",
                List.of("windows"),
                "x64",
                List.of("productivity"),
                null,
                List.of("Code Sector"),
                1,
                "all");

        verify(catalog).facets(
                isNull(),
                eq("review"),
                eq(List.of("windows")),
                eq("x64"),
                eq(List.of("productivity")),
                eq(List.of("Code Sector")),
                eq(1),
                eq("all"));
    }

    /**
     * Comprueba el escenario {@code unresolvedRemainsAnAdministrativeFilterAndIsRejectedPublicly}.
     */
    @Test
    void unresolvedRemainsAnAdministrativeFilterAndIsRejectedPublicly() {
        CatalogController controller = new CatalogController(catalog);

        assertThatThrownBy(() -> controller.apps(
                        null,
                        "unresolved",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "all",
                        "name",
                        1,
                        20))
                .isInstanceOf(BadRequestException.class)
                .extracting(exception -> ((BadRequestException) exception).code())
                .isEqualTo("invalid_catalog_status");
    }
}

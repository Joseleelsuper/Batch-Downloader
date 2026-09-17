package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.BadRequestException;
import java.net.http.HttpClient;
import java.time.Duration;
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

    /** Comprueba que el orden por nombre publica el índice de la misma búsqueda. */
    @Test
    void nameOrderingIncludesAlphabetPositions() {
        List<CatalogDtos.CatalogAlphabetEntry> alphabet = List.of(
                new CatalogDtos.CatalogAlphabetEntry("A", 1, 15),
                new CatalogDtos.CatalogAlphabetEntry("C", 3, 13));
        when(catalog.search(any(CatalogQuery.class), any(), anyInt(), anyInt(), any(SemanticCandidateSet.class)))
                .thenReturn(List.of());
        when(catalog.alphabet(any(CatalogQuery.class), anyInt(), any(SemanticCandidateSet.class)))
                .thenReturn(alphabet);
        CatalogController controller = controller();

        CatalogDtos.AppSearchResponse response = controller.apps(
                null, "all", null, null, null, null, "name", 1, 12, "lexical");

        assertThat(response.alphabet()).isEqualTo(alphabet);
        verify(catalog).alphabet(eq(new CatalogQuery(null, "all", List.of(), null, List.of(), List.of())), eq(12), any(SemanticCandidateSet.class));
    }

    /**
     * Comprueba que las tags repetidas y un editor singular usan coincidencia completa.
     */
    @Test
    void appsUsesRepeatedTagsAndOnePublisherWithAllMatching() {
        when(catalog.search(any(CatalogQuery.class), any(), anyInt(), anyInt(), any(SemanticCandidateSet.class)))
                .thenReturn(List.of());
        CatalogController controller = controller();

        controller.apps(
                "epic",
                "available",
                null,
                null,
                List.of(".NET", "runtime"),
                "ACME, Inc.",
                "updated",
                1,
                20,
                "lexical");

        verify(catalog).search(eq(new CatalogQuery("epic", "available", List.of(), null, List.of(".NET", "runtime"), List.of("ACME, Inc."))), eq("updated"), eq(1), eq(20), any(SemanticCandidateSet.class));
    }

    /**
     * Comprueba el escenario {@code facetsParsesTheSameFilterContractAsApps}.
     */
    @Test
    void facetsParsesTheSameFilterContractAsApps() {
        when(catalog.facets(any(CatalogQuery.class), any(SemanticCandidateSet.class)))
                .thenReturn(new CatalogDtos.CatalogFacetsResponse(List.of(), List.of()));
        CatalogController controller = controller();

        controller.facets(null, "review", List.of("windows"), "x64", List.of("productivity"), "Code Sector", "lexical");

        verify(catalog).facets(eq(new CatalogQuery(null, "review", List.of("windows"), "x64", List.of("productivity"), List.of("Code Sector"))), any(SemanticCandidateSet.class));
    }

    /**
     * Comprueba el escenario {@code unresolvedRemainsAnAdministrativeFilterAndIsRejectedPublicly}.
     */
    @Test
    void unresolvedRemainsAnAdministrativeFilterAndIsRejectedPublicly() {
        CatalogController controller = controller();

        assertThatThrownBy(() -> controller.apps(
                        null,
                        "unresolved",
                        null,
                        null,
                        null,
                        null,
                        "name",
                        1,
                        20,
                        "lexical"))
                .isInstanceOf(BadRequestException.class)
                .extracting(exception -> ((BadRequestException) exception).code())
                .isEqualTo("invalid_catalog_status");
    }

    /** Construye el controlador con colaboradores deshabilitados de forma explícita. */
    private CatalogController controller() {
        return new CatalogController(
                catalog,
                new SemanticSearchClient(
                        HttpClient.newHttpClient(),
                        new ObjectMapper(),
                        "http://semantic.invalid",
                        "test-token",
                        Duration.ofSeconds(1)),
                new PublicCatalogCache(0, Duration.ofMillis(1)));
    }
}

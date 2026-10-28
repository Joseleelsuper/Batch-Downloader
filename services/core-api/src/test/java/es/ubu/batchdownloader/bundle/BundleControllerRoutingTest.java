package es.ubu.batchdownloader.bundle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import es.ubu.batchdownloader.admin.AdminAuditService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifica que el controlador de bundles solo publique el contrato versionado. */
class BundleControllerRoutingTest {
    private BundleRepository bundles;
    private BundleCatalogService catalog;
    private MockMvc mvc;

    /** Crea un controlador aislado para comprobar exclusivamente su tabla de rutas. */
    @BeforeEach
    void setUp() {
        bundles = mock(BundleRepository.class);
        catalog = mock(BundleCatalogService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new BundleController(bundles, mock(AdminAuditService.class), catalog,
                                mock(BundleActivityRepository.class), mock(es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount.class)))
                .build();
    }

    /** El contrato vigente permanece disponible bajo {@code /api/v1}. */
    @Test
    void exposesVersionedCollectionRoute() throws Exception {
        when(catalog.search(any(), eq("updated"), eq(1), eq(12), any()))
                .thenReturn(new BundleDtos.BundleCatalogResponse(List.of(),1,12,0,java.util.Map.of(),List.of(),"lexical","lexical",null,null,null));

        mvc.perform(get("/api/v1/bundles"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data").isEmpty());
        verify(catalog).search(new BundleCatalogRepository.Filters(null,null,null,null,null,"lexical"),"updated",1,12,null);
    }

    /** El alias incompatible retirado deja de resolverse. */
    @Test
    void rejectsRemovedUnversionedCollectionRoute() throws Exception {
        mvc.perform(get("/api/bundles"))
                .andExpect(status().isNotFound());
    }

    @Test void facetEndpointDoesNotShadowAnExistingBundleSlug() throws Exception {
        String id="00000000-0000-0000-0000-000000000001";
        when(bundles.details("facets",null,false)).thenReturn(new BundleDtos.BundleDetails(id,"facets","Facets",null,
                "user","public",0,0,List.of(),List.of(),List.of(),List.of(),java.time.LocalDateTime.of(2026,10,27,0,0)));
        when(catalog.facets(any())).thenReturn(new BundleDtos.BundleFacets(List.of(),List.of()));
        mvc.perform(get("/api/v1/bundles/facets")).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("facets"));
        mvc.perform(get("/api/v1/bundles/catalog/facets")).andExpect(status().isOk()).andExpect(jsonPath("$.tags").isEmpty());
    }

    @Test void administrativeListingKeepsItsExistingPageContract() throws Exception {
        when(bundles.listForAdministration(null,"updated",1,20)).thenReturn(List.of());
        when(bundles.countForAdministration(null)).thenReturn(0L);
        mvc.perform(get("/api/v1/admin/bundles")).andExpect(status().isOk())
                .andExpect(jsonPath("$.pageSize").value(20)).andExpect(jsonPath("$.data").isEmpty());
    }
}

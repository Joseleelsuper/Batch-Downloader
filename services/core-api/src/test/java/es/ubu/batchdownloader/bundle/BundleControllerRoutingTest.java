package es.ubu.batchdownloader.bundle;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ubu.batchdownloader.admin.AdminAuditService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifica que el controlador de bundles solo publique el contrato versionado. */
class BundleControllerRoutingTest {
    private BundleRepository bundles;
    private MockMvc mvc;

    /** Crea un controlador aislado para comprobar exclusivamente su tabla de rutas. */
    @BeforeEach
    void setUp() {
        bundles = mock(BundleRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new BundleController(bundles, mock(AdminAuditService.class)))
                .build();
    }

    /** El contrato vigente permanece disponible bajo {@code /api/v1}. */
    @Test
    void exposesVersionedCollectionRoute() throws Exception {
        when(bundles.list(null, "updated", 1, 12)).thenReturn(List.of());
        when(bundles.count(null)).thenReturn(0L);

        mvc.perform(get("/api/v1/bundles"))
                .andExpect(status().isOk());
    }

    /** El alias incompatible retirado deja de resolverse. */
    @Test
    void rejectsRemovedUnversionedCollectionRoute() throws Exception {
        mvc.perform(get("/api/bundles"))
                .andExpect(status().isNotFound());
    }
}

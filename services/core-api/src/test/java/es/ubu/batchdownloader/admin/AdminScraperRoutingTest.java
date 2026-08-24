package es.ubu.batchdownloader.admin;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifica que ya no existan rutas capaces de borrar trabajo pendiente o arrendado. */
class AdminScraperRoutingTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminScraperController(
                        mock(AdminScraperRepository.class),
                        mock(AdminAuditService.class),
                        mock(ScraperInternalClient.class)))
                .build();
    }

    @Test
    void rejectsRemovedClearPendingRoute() throws Exception {
        mvc.perform(post("/api/v1/admin/scraper/queues/clear-pending"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRemovedClearAllRoute() throws Exception {
        mvc.perform(post("/api/v1/admin/scraper/queues/clear-all"))
                .andExpect(status().isNotFound());
    }
}

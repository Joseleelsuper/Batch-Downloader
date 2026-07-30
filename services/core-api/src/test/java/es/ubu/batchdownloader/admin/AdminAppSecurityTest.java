package es.ubu.batchdownloader.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspection;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscovery;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Agrupa los escenarios de prueba de {@code AdminAppSecurityTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@WebMvcTest(AdminAppController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "server.port=0",
    "server.servlet.session.cookie.secure=false",
    "spring.session.timeout=30m",
    "app.security.require-https=false",
    "app.auth.bcrypt-strength=4"
})
class AdminAppSecurityTest {
    /**
     * Constante que define {@code APP_ID}.
     */
    private static final String APP_ID = "00000000-0000-0000-0000-000000000001";

    /**
     * Dato compartido {@code mvc} para los escenarios de prueba.
     */
    @Autowired
    private MockMvc mvc;

    /**
     * Dato compartido {@code catalog} para los escenarios de prueba.
     */
    @MockBean
    private CatalogRepository catalog;

    /**
     * Dato compartido {@code adminApps} para los escenarios de prueba.
     */
    @MockBean
    private AdminAppRepository adminApps;

    /**
     * Dato compartido {@code audit} para los escenarios de prueba.
     */
    @MockBean
    private AdminAuditService audit;

    /**
     * Dato compartido {@code scraperClient} para los escenarios de prueba.
     */
    @MockBean
    private ScraperInternalClient scraperClient;

    /**
     * Dato compartido {@code users} para los escenarios de prueba.
     */
    @MockBean
    private UserAccountStore users;

    /**
     * Comprueba el escenario {@code inspectionEndpointsRequireAnAdministratorSession}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void inspectionEndpointsRequireAnAdministratorSession() throws Exception {
        mvc.perform(get(
                        "/api/admin/apps/{appId}/manual-installer-inspections/current",
                        APP_ID))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Comprueba el escenario {@code inspectionCreationRejectsAnAdministratorWithoutCsrf}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void inspectionCreationRejectsAnAdministratorWithoutCsrf() throws Exception {
        mvc.perform(post(
                        "/api/admin/apps/{appId}/manual-installer-inspections",
                        APP_ID)
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content(validRequest()))
                .andExpect(status().isForbidden());
    }

    /**
     * Comprueba el escenario {@code inspectionCreationAcceptsAnAdministratorWithCsrf}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void inspectionCreationAcceptsAnAdministratorWithCsrf() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(scraperClient.createManualInstallerInspection(anyString(), any()))
                .thenReturn(new ManualInstallerInspection(
                        "00000000-0000-0000-0000-000000000002",
                        APP_ID,
                        "queued",
                        "queued",
                        0,
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        now,
                        now,
                        now.plusHours(24)));

        mvc.perform(post(
                        "/api/admin/apps/{appId}/manual-installer-inspections",
                        APP_ID)
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(validRequest()))
                .andExpect(status().isAccepted());
    }

    /**
     * Comprueba el escenario {@code websiteDiscoveryCreationRequiresCsrf}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void websiteDiscoveryCreationRequiresCsrf() throws Exception {
        mvc.perform(post("/api/admin/app-discoveries")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content(websiteDiscoveryRequest()))
                .andExpect(status().isForbidden());
    }

    /**
     * Comprueba el escenario {@code websiteDiscoveryCreationAcceptsAnAdministratorWithCsrf}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void websiteDiscoveryCreationAcceptsAnAdministratorWithCsrf() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        when(scraperClient.createWebsiteAppDiscovery(any()))
                .thenReturn(new WebsiteAppDiscovery(
                        "00000000-0000-0000-0000-000000000003",
                        "queued",
                        "queued",
                        List.of(),
                        List.of("windows"),
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        now,
                        now,
                        now.plusHours(24)));

        mvc.perform(post("/api/admin/app-discoveries")
                .with(user("admin").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content(websiteDiscoveryRequest()))
                .andExpect(status().isAccepted());
    }

    /**
     * Ejecuta la operación {@code validRequest}.
     *
     * @return Resultado producido por {@code validRequest}.
     */
    private String validRequest() {
        return """
                {
                  "installerUrls":{
                    "windows":"https://downloads.example.test/App.exe",
                    "macos":null,
                    "linux":"https://downloads.example.test/app.AppImage"
                  },
                  "sourcePageUrl":"https://example.test/download"
                }
                """;
    }

    /**
     * Ejecuta la operación {@code websiteDiscoveryRequest}.
     *
     * @return Resultado producido por {@code websiteDiscoveryRequest}.
     */
    private String websiteDiscoveryRequest() {
        return """
                {
                  "officialUrl":"https://example.test/product",
                  "installerUrls":{
                    "windows":"https://downloads.example.test/Product.exe",
                    "macos":null,
                    "linux":null
                  }
                }
                """;
    }
}

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
    private static final String APP_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CatalogRepository catalog;

    @MockBean
    private AdminAppRepository adminApps;

    @MockBean
    private AdminAuditService audit;

    @MockBean
    private ScraperInternalClient scraperClient;

    @MockBean
    private UserAccountStore users;

    @Test
    void inspectionEndpointsRequireAnAdministratorSession() throws Exception {
        mvc.perform(get(
                        "/api/admin/apps/{appId}/manual-installer-inspections/current",
                        APP_ID))
                .andExpect(status().isUnauthorized());
    }

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

    @Test
    void websiteDiscoveryCreationRequiresCsrf() throws Exception {
        mvc.perform(post("/api/admin/app-discoveries")
                .with(user("admin").roles("ADMIN"))
                .contentType("application/json")
                .content(websiteDiscoveryRequest()))
                .andExpect(status().isForbidden());
    }

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

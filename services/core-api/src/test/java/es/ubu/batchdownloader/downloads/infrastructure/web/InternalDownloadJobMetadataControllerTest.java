package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadJobService.DownloadItemMetadata;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalDownloadJobMetadataController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "server.port=0",
    "server.servlet.session.cookie.secure=false",
    "spring.session.timeout=30m",
    "app.security.require-https=true",
    "app.auth.bcrypt-strength=4",
    "app.scraper-internal-service-token=test-internal-service-token"
})
class InternalDownloadJobMetadataControllerTest {
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID APP_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DownloadJobService jobs;

    @MockBean
    private UserAccountStore users;

    @Test
    void acceptsContainerHttpWithoutCsrfWhenTheInternalTokenMatches() throws Exception {
        when(jobs.itemMetadata(eq(JOB_ID), any())).thenReturn(List.of(
                new DownloadItemMetadata(
                        ITEM_ID,
                        APP_ID,
                        "Aplicación fallida",
                        "https://example.com/app")));

        mvc.perform(post(
                        "/internal/v1/download-jobs/{jobId}/item-metadata",
                        JOB_ID)
                .header("X-Internal-Service-Token", "test-internal-service-token")
                .contentType("application/json")
                .content("{\"itemIds\":[\"" + ITEM_ID + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value(ITEM_ID.toString()))
                .andExpect(jsonPath("$[0].appId").value(APP_ID.toString()))
                .andExpect(jsonPath("$[0].appName").value("Aplicación fallida"))
                .andExpect(jsonPath("$[0].officialPageUrl").value("https://example.com/app"));
    }

    @Test
    void rejectsMissingOrIncorrectInternalTokens() throws Exception {
        String body = "{\"itemIds\":[\"" + ITEM_ID + "\"]}";

        mvc.perform(post(
                        "/internal/v1/download-jobs/{jobId}/item-metadata",
                        JOB_ID)
                .contentType("application/json")
                .content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(
                        "/internal/v1/download-jobs/{jobId}/item-metadata",
                        JOB_ID)
                .header("X-Internal-Service-Token", "incorrect-token")
                .contentType("application/json")
                .content(body))
                .andExpect(status().isUnauthorized());
    }
}

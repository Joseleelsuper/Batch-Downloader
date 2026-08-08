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
import es.ubu.batchdownloader.identity.infrastructure.security.GoogleOAuthFailureHandler;
import es.ubu.batchdownloader.identity.infrastructure.security.GoogleOAuthSuccessHandler;
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

/**
 * Agrupa los escenarios de prueba de {@code InternalDownloadJobMetadataControllerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@WebMvcTest(InternalDownloadJobMetadataController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "server.port=0",
    "server.servlet.session.cookie.secure=false",
    "spring.session.timeout=30m",
    "app.security.require-https=true",
    "app.auth.bcrypt-strength=4",
    "app.auth.hash-concurrency=2",
    "app.auth.hash-queue=20",
    "app.auth.hash-wait=2s",
    "app.scraper-internal-service-token=test-internal-service-token"
})
class InternalDownloadJobMetadataControllerTest {
    /**
     * Constante que define {@code JOB_ID}.
     */
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    /**
     * Constante que define {@code ITEM_ID}.
     */
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    /**
     * Constante que define {@code APP_ID}.
     */
    private static final UUID APP_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

    /**
     * Dato compartido {@code mvc} para los escenarios de prueba.
     */
    @Autowired
    private MockMvc mvc;

    /**
     * Dato compartido {@code jobs} para los escenarios de prueba.
     */
    @MockBean
    private DownloadJobService jobs;

    /**
     * Dato compartido {@code users} para los escenarios de prueba.
     */
    @MockBean
    private UserAccountStore users;

    @MockBean
    private GoogleOAuthSuccessHandler googleOAuthSuccessHandler;

    @MockBean
    private GoogleOAuthFailureHandler googleOAuthFailureHandler;

    /**
     * Comprueba el escenario {@code acceptsContainerHttpWithoutCsrfWhenTheInternalTokenMatches}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
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

    /**
     * Comprueba el escenario {@code rejectsMissingOrIncorrectInternalTokens}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
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

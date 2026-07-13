package es.ubu.batchdownloader.translation.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import es.ubu.batchdownloader.translation.application.GetLocale;
import es.ubu.batchdownloader.translation.application.port.LocaleCatalog;
import es.ubu.batchdownloader.translation.config.TranslationProperties;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocaleControllerTest {

    private static final String ETAG = "\"8d7c294d0c4f3f5a\"";
    private static final byte[] CONTENT = "{\"greeting\":\"Hola\"}"
            .getBytes(StandardCharsets.UTF_8);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocaleDocument document = new LocaleDocument("es", CONTENT, ETAG);
        LocaleCatalog catalog = locale -> "es".equals(locale)
                ? Optional.of(document)
                : Optional.empty();
        GetLocale getLocale = new GetLocale(catalog);
        TranslationProperties properties = new TranslationProperties(
                Path.of("locales"), Duration.ofHours(1));
        LocaleController controller = new LocaleController(getLocale, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsTheSpanishCatalogWithCacheHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/locales/es"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(content().bytes(CONTENT))
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=3600"),
                                org.hamcrest.Matchers.containsString("public"),
                                org.hamcrest.Matchers.containsString("must-revalidate"))));
    }

    @Test
    void returnsNotModifiedWhenIfNoneMatchMatches() throws Exception {
        mockMvc.perform(get("/api/v1/locales/es").header(HttpHeaders.IF_NONE_MATCH, ETAG))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, ETAG))
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    void exposesOnlyTheSpanishLocaleInVersionOne() throws Exception {
        mockMvc.perform(get("/api/v1/locales/en"))
                .andExpect(status().isNotFound());
    }
}

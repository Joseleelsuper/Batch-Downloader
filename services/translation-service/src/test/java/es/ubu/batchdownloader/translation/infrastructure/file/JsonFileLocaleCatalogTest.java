package es.ubu.batchdownloader.translation.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.translation.config.TranslationProperties;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agrupa los escenarios de prueba de {@code JsonFileLocaleCatalogTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class JsonFileLocaleCatalogTest {

    /**
     * Dato compartido {@code localeDirectory} para los escenarios de prueba.
     */
    @TempDir
    private Path localeDirectory;

    /**
     * Comprueba el escenario {@code loadsAndCachesAValidSpanishCatalog}.
     *
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    @Test
    void loadsAndCachesAValidSpanishCatalog() throws IOException {
        write("template", "shared.json", "{\"greeting\":\"\"}");
        write("template", "home.json", "{\"farewell\":\"\"}");
        write("es", "shared.json", "{\"greeting\":\"Hola\"}");
        write("es", "home.json", "{\"farewell\":\"Adiós\"}");

        JsonFileLocaleCatalog catalog = catalog();

        LocaleDocument first = catalog.findByLocale("es").orElseThrow();
        LocaleDocument second = catalog.findByLocale("es").orElseThrow();
        assertThat(first).isSameAs(second);
        assertThat(new ObjectMapper().readTree(first.content()))
                .isEqualTo(new ObjectMapper().readTree(
                        "{\"greeting\":\"Hola\",\"farewell\":\"Adiós\"}"));
        assertThat(first.etag()).matches("\"[0-9a-f]{64}\"");
        assertThat(catalog.findByLocale("en")).isEmpty();
    }

    /** Comprueba que el catálogo real conserva todas las claves tras dividirse por páginas. */
    @Test
    void loadsTheRepositoryPageCatalogWithoutLosingMessages() throws IOException {
        JsonFileLocaleCatalog catalog = new JsonFileLocaleCatalog(
                new TranslationProperties(Path.of("locales"), Duration.ofHours(1)));

        LocaleDocument spanish = catalog.findByLocale("es").orElseThrow();
        JsonNode messages = new ObjectMapper().readTree(spanish.content());
        assertThat(messages.size()).isEqualTo(802);
        assertThat(messages.has("catalog.title")).isTrue();
        assertThat(messages.has("admin.apps.subtitle")).isTrue();
        assertThat(messages.has("account.login.title")).isTrue();
        assertThat(messages.has("error.google_oauth_not_configured.title")).isTrue();
        assertThat(messages.has("legal.privacy.title")).isTrue();
        assertThat(messages.has("legal.lastUpdated")).isTrue();
        assertThat(messages.has("download.job.manual.title")).isTrue();
        assertThat(messages.has("download.job.apiError.service_busy")).isTrue();
        assertThat(messages.has("admin.scraper.clearAll")).isFalse();
        assertThat(messages.has("admin.scraper.clearPending")).isFalse();
        assertThat(messages.has("semantic.artifact.downloading")).isFalse();
        assertThat(messages.has("semantic.operation.download")).isFalse();
    }

    /**
     * Comprueba el escenario {@code failsFastWhenTheSpanishCatalogMissesATemplateKey}.
     *
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    @Test
    void failsFastWhenTheSpanishCatalogMissesATemplateKey() throws IOException {
        write("template", "home.json", "{\"greeting\":\"\",\"farewell\":\"\"}");
        write("es", "home.json", "{\"greeting\":\"Hola\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("farewell");
    }

    /**
     * Comprueba el escenario {@code failsFastWhenTheSpanishCatalogAddsAnUnknownKey}.
     *
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    @Test
    void failsFastWhenTheSpanishCatalogAddsAnUnknownKey() throws IOException {
        write("template", "home.json", "{\"greeting\":\"\"}");
        write("es", "home.json", "{\"greeting\":\"Hola\",\"unknown\":\"No\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("unknown");
    }

    /**
     * Comprueba el escenario {@code rejectsBlankOrNonTextTranslations}.
     *
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    @Test
    void rejectsBlankOrNonTextTranslations() throws IOException {
        write("template", "home.json", "{\"greeting\":\"\"}");
        write("es", "home.json", "{\"greeting\":\"   \"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("greeting");

        write("es", "home.json", "{\"greeting\":42}");
        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("greeting");
    }

    /**
     * Comprueba el escenario {@code rejectsDuplicateJsonKeys}.
     *
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    @Test
    void rejectsDuplicateJsonKeys() throws IOException {
        write("template", "home.json", "{\"greeting\":\"\"}");
        write("es", "home.json", "{\"greeting\":\"Hola\",\"greeting\":\"Buenas\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("JSON estricto");
    }

    /** Comprueba que cada página de plantilla tenga su equivalente traducido. */
    @Test
    void rejectsMissingLocalePage() throws IOException {
        write("template", "home.json", "{\"greeting\":\"\"}");
        write("template", "catalog.json", "{\"search\":\"\"}");
        write("es", "home.json", "{\"greeting\":\"Hola\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("catalog.json");
    }

    /** Comprueba que una clave no pueda pertenecer a dos páginas. */
    @Test
    void rejectsKeysDuplicatedAcrossPages() throws IOException {
        write("template", "home.json", "{\"greeting\":\"\"}");
        write("template", "shared.json", "{\"greeting\":\"\"}");
        write("es", "home.json", "{\"greeting\":\"Hola\"}");
        write("es", "shared.json", "{\"greeting\":\"Buenas\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("greeting");
    }

    /**
     * Ejecuta la operación {@code catalog}.
     *
     * @return Resultado producido por {@code catalog}.
     */
    private JsonFileLocaleCatalog catalog() {
        return new JsonFileLocaleCatalog(
                new TranslationProperties(localeDirectory, Duration.ofHours(1)));
    }

    /**
     * Ejecuta la operación {@code write}.
     *
     * @param catalogName Directorio de plantilla o idioma.
     * @param fileName Nombre de la página JSON.
     * @param content Contenido que debe procesarse.
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    private void write(String catalogName, String fileName, String content) throws IOException {
        Path directory = Files.createDirectories(localeDirectory.resolve(catalogName));
        Files.writeString(directory.resolve(fileName), content, StandardCharsets.UTF_8);
    }
}

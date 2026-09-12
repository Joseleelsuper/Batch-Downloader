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
 * Comprueba carga, caché, paridad y rechazo de duplicados del catálogo dividido en páginas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.infrastructure.file.JsonFileLocaleCatalog
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
class JsonFileLocaleCatalogTest {

    /**
     * Dato compartido {@code localeDirectory} para los escenarios de prueba.
     */
    @TempDir
    private Path localeDirectory;

    /**
     * Comprueba que una estructura válida publica el JSON español y lo conserva en caché.
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
        ObjectMapper mapper = new ObjectMapper();
        int expectedMessages = 0;
        try (var pages = Files.list(Path.of("locales", "es"))) {
            for (Path page : pages.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonNode entries = mapper.readTree(page.toFile());
                expectedMessages += entries.size();
                entries.properties().forEach(entry ->
                        assertThat(messages.get(entry.getKey())).isEqualTo(entry.getValue()));
            }
        }
        assertThat(messages.size()).isEqualTo(expectedMessages);
        assertThat(messages.has("catalog.title")).isTrue();
        assertThat(messages.has("admin.apps.subtitle")).isTrue();
        assertThat(messages.has("account.login.title")).isTrue();
        assertThat(messages.has("error.unexpected_error.title")).isTrue();
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
     * Comprueba que falta de una clave de plantilla impide construir el catálogo español.
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
     * Comprueba que una traducción sin clave equivalente en la plantilla se rechaza al cargar.
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
     * Comprueba que las traducciones españolas vacías o no textuales impiden publicar el catálogo.
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
     * Comprueba que repetir una clave dentro de una página JSON produce un error de configuración.
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
     * Construye un catálogo desde las páginas temporales creadas por el escenario.
     *
     * @return catálogo validado, o propaga el fallo esperado por la prueba.
     */
    private JsonFileLocaleCatalog catalog() {
        return new JsonFileLocaleCatalog(
                new TranslationProperties(localeDirectory, Duration.ofHours(1)));
    }

    /**
     * Escribe una página JSON controlada bajo el directorio temporal del catálogo elegido.
     *
     * @param catalogName Directorio del catálogo temporal: plantilla o español.
     * @param fileName Nombre de la página JSON del escenario.
     * @param content Texto JSON que se escribirá, válido o inválido según la prueba.
     */
    private void write(String catalogName, String fileName, String content) throws IOException {
        Path directory = Files.createDirectories(localeDirectory.resolve(catalogName));
        Files.writeString(directory.resolve(fileName), content, StandardCharsets.UTF_8);
    }
}

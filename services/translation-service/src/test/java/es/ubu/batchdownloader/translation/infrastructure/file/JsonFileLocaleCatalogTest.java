package es.ubu.batchdownloader.translation.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.translation.config.TranslationProperties;
import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonFileLocaleCatalogTest {

    @TempDir
    private Path localeDirectory;

    @Test
    void loadsAndCachesAValidSpanishCatalog() throws IOException {
        write("template.json", "{\"greeting\":\"\",\"farewell\":\"\"}");
        byte[] expectedContent = "{\"greeting\":\"Hola\",\"farewell\":\"Adiós\"}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(localeDirectory.resolve("es.json"), expectedContent);

        JsonFileLocaleCatalog catalog = catalog();

        LocaleDocument first = catalog.findByLocale("es").orElseThrow();
        LocaleDocument second = catalog.findByLocale("es").orElseThrow();
        assertThat(first).isSameAs(second);
        assertThat(first.content()).isEqualTo(expectedContent);
        assertThat(first.etag()).matches("\"[0-9a-f]{64}\"");
        assertThat(catalog.findByLocale("en")).isEmpty();
    }

    @Test
    void failsFastWhenTheSpanishCatalogMissesATemplateKey() throws IOException {
        write("template.json", "{\"greeting\":\"\",\"farewell\":\"\"}");
        write("es.json", "{\"greeting\":\"Hola\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("farewell");
    }

    @Test
    void failsFastWhenTheSpanishCatalogAddsAnUnknownKey() throws IOException {
        write("template.json", "{\"greeting\":\"\"}");
        write("es.json", "{\"greeting\":\"Hola\",\"unknown\":\"No\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void rejectsBlankOrNonTextTranslations() throws IOException {
        write("template.json", "{\"greeting\":\"\"}");
        write("es.json", "{\"greeting\":\"   \"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("greeting");

        write("es.json", "{\"greeting\":42}");
        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("greeting");
    }

    @Test
    void rejectsDuplicateJsonKeys() throws IOException {
        write("template.json", "{\"greeting\":\"\"}");
        write("es.json", "{\"greeting\":\"Hola\",\"greeting\":\"Buenas\"}");

        assertThatThrownBy(this::catalog)
                .isInstanceOf(LocaleCatalogConfigurationException.class)
                .hasMessageContaining("JSON estricto");
    }

    private JsonFileLocaleCatalog catalog() {
        return new JsonFileLocaleCatalog(
                new TranslationProperties(localeDirectory, Duration.ofHours(1)));
    }

    private void write(String fileName, String content) throws IOException {
        Files.writeString(localeDirectory.resolve(fileName), content, StandardCharsets.UTF_8);
    }
}

package es.ubu.batchdownloader.translation.infrastructure.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.translation.domain.LocaleDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifica que la métrica no cambia la semántica exacta del catálogo. */
class MeteredLocaleCatalogTest {

    @Test
    void recordsHitsAndMissesWithoutUsingLocaleAsATag() {
        JsonFileLocaleCatalog delegate = mock(JsonFileLocaleCatalog.class);
        LocaleDocument spanish = new LocaleDocument(
                "es",
                "{}".getBytes(StandardCharsets.UTF_8),
                "etag");
        when(delegate.findByLocale("es")).thenReturn(Optional.of(spanish));
        when(delegate.findByLocale("ES")).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredLocaleCatalog catalog = new MeteredLocaleCatalog(
                delegate,
                Optional.of(registry));

        assertThat(catalog.findByLocale("es")).contains(spanish);
        assertThat(catalog.findByLocale("ES")).isEmpty();

        assertThat(registry.get("translation_locale_lookup")
                .tag("outcome", "hit")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.get("translation_locale_lookup")
                .tag("outcome", "miss")
                .counter()
                .count()).isEqualTo(1);
        assertThat(registry.find("translation_locale_lookup").meters())
                .allMatch(meter -> meter.getId().getTags().stream()
                        .noneMatch(tag -> tag.getKey().equals("locale")));
    }
}

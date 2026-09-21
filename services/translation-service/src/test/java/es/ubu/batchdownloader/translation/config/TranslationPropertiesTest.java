package es.ubu.batchdownloader.translation.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TranslationPropertiesTest {

    @Test
    void rejectsNonPositiveCacheDurations() {
        assertThatThrownBy(() -> new TranslationProperties(Path.of("locales"), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("translation.cache-max-age debe ser positivo");
    }
}

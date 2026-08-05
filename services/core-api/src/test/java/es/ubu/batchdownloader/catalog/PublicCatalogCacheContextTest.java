package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Comprueba el registro real de la caché pública en el contenedor de Spring. */
class PublicCatalogCacheContextTest {
    /** Verifica que Spring selecciona el constructor configurado con sus dos propiedades. */
    @Test
    void springCreatesPublicCatalogCacheWithConfiguredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "catalogCacheTest",
                    Map.of(
                            "app.catalog.cache-maximum-size", "512",
                            "app.catalog.cache-ttl", "15s")));
            context.register(PublicCatalogCache.class);
            context.refresh();

            assertThat(context.getBean(PublicCatalogCache.class)).isNotNull();
        }
    }
}

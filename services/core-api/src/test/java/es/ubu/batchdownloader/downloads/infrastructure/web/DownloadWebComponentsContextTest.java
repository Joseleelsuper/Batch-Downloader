package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Verifica los constructores de producción de los componentes web de descargas. */
class DownloadWebComponentsContextTest {
    /** Comprueba que Spring resuelve los constructores configurados aunque existan auxiliares de prueba. */
    @Test
    void springCreatesDownloadWebComponentsWithConfiguredConstructors() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "downloadWebComponentsTest",
                    Map.of(
                            "app.download.sse-heartbeat", "20s",
                            "app.download.worker-capacity-url", "http://download-worker:8080",
                            "app.download.worker-capacity-timeout", "2s",
                            "app.scraper-internal-service-token", "test-token")));
            context.register(SseDownloadJobNotifier.class, DownloadWorkerCapacityClient.class);
            context.refresh();

            assertThat(context.getBean(SseDownloadJobNotifier.class)).isNotNull();
            assertThat(context.getBean(DownloadWorkerCapacityClient.class)).isNotNull();
        }
    }
}

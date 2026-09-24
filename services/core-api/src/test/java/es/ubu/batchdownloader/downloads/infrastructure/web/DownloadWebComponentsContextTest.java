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
            context.registerBean(es.ubu.batchdownloader.downloads.application.port.DownloadJobStore.class,
                    () -> org.mockito.Mockito.mock(es.ubu.batchdownloader.downloads.application.port.DownloadJobStore.class));
            context.registerBean(es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher.class,
                    () -> org.mockito.Mockito.mock(es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher.class));
            context.registerBean(es.ubu.batchdownloader.downloads.application.port.DownloadStorage.class,
                    () -> org.mockito.Mockito.mock(es.ubu.batchdownloader.downloads.application.port.DownloadStorage.class));
            context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,
                    () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class));
            context.registerBean(org.springframework.transaction.support.TransactionTemplate.class,
                    () -> org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class));
            context.registerBean(java.time.Clock.class, java.time.Clock::systemUTC);
            context.register(SseDownloadJobNotifier.class,
                    es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator.class);
            context.refresh();

            assertThat(context.getBean(SseDownloadJobNotifier.class)).isNotNull();
        }
    }
}

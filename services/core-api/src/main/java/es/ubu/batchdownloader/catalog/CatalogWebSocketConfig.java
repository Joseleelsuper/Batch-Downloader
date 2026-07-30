package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.admin.AdminScraperNotifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Define la configuración utilizada por {@code CatalogWebSocketConfig}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
@EnableWebSocket
@EnableScheduling
public class CatalogWebSocketConfig implements WebSocketConfigurer {
    /**
     * Estado {@code notifier} mantenido por {@code CatalogWebSocketConfig}.
     */
    private final CatalogChangeNotifier notifier;
    /**
     * Estado {@code scraperNotifier} mantenido por {@code CatalogWebSocketConfig}.
     */
    private final AdminScraperNotifier scraperNotifier;

    /**
     * Inicializa una instancia de {@code CatalogWebSocketConfig}.
     *
     * @param notifier Valor de {@code notifier} utilizado por la operación.
     * @param scraperNotifier Valor de {@code scraperNotifier} utilizado por la operación.
     */
    public CatalogWebSocketConfig(CatalogChangeNotifier notifier, AdminScraperNotifier scraperNotifier) {
        this.notifier = notifier;
        this.scraperNotifier = scraperNotifier;
    }

    /**
     * Implementa {@code registerWebSocketHandlers} para {@code CatalogWebSocketConfig}.
     *
     * @param registry Valor de {@code registry} utilizado por la operación.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifier, "/api/catalog/ws").setAllowedOriginPatterns("*");
        registry.addHandler(scraperNotifier, "/api/admin/scraper/ws").setAllowedOriginPatterns("*");
    }
}

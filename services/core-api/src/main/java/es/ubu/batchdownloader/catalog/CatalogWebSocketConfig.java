package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.admin.AdminScraperNotifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class CatalogWebSocketConfig implements WebSocketConfigurer {
    private final CatalogChangeNotifier notifier;
    private final AdminScraperNotifier scraperNotifier;

    public CatalogWebSocketConfig(CatalogChangeNotifier notifier, AdminScraperNotifier scraperNotifier) {
        this.notifier = notifier;
        this.scraperNotifier = scraperNotifier;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifier, "/api/catalog/ws").setAllowedOriginPatterns("*");
        registry.addHandler(scraperNotifier, "/api/admin/scraper/ws").setAllowedOriginPatterns("*");
    }
}

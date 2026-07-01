package es.ubu.batchdownloader.catalog;

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

    public CatalogWebSocketConfig(CatalogChangeNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifier, "/api/catalog/ws").setAllowedOriginPatterns("*");
    }
}

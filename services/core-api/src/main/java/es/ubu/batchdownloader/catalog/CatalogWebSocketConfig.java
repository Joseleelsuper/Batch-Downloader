package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.admin.AdminScraperNotifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registra los canales público y administrativo de actualizaciones con un origen de navegador
 * explícitamente permitido.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogChangeNotifier
 * @see es.ubu.batchdownloader.admin.AdminScraperNotifier
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
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
    private final String publicBaseUrl;

    /**
     * Conecta los dos difusores y el origen público autorizado para sus conexiones.
     *
     * @param notifier Difusor de invalidaciones públicas del catálogo por WebSocket.
     * @param scraperNotifier Difusor administrativo del estado del scraper, protegido por la
     *     seguridad de su ruta.
     * @param publicBaseUrl Origen exacto del frontend permitido para establecer las conexiones
     *     WebSocket.
     */
    public CatalogWebSocketConfig(
            CatalogChangeNotifier notifier,
            AdminScraperNotifier scraperNotifier,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.notifier = notifier;
        this.scraperNotifier = scraperNotifier;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Asocia los difusores con /api/v1/catalog/ws y /api/v1/admin/scraper/ws bajo el origen
     * permitido.
     *
     * @param registry Registro de endpoints WebSocket del contexto Spring.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifier, "/api/v1/catalog/ws").setAllowedOrigins(publicBaseUrl);
        registry.addHandler(scraperNotifier, "/api/v1/admin/scraper/ws").setAllowedOrigins(publicBaseUrl);
    }
}

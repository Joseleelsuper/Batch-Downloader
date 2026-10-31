package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.admin.AdminScraperNotifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registra el canal administrativo de actualizaciones con un origen de navegador
 * explícitamente permitido.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Estado {@code scraperNotifier} mantenido por {@code CatalogWebSocketConfig}.
     */
    private final AdminScraperNotifier scraperNotifier;
    private final String publicBaseUrl;

    /**
     * Conecta el difusor administrativo y el origen público autorizado.
     *
     * @param scraperNotifier Difusor administrativo del estado del scraper, protegido por la
     *     seguridad de su ruta.
     * @param publicBaseUrl Origen exacto del frontend permitido para establecer las conexiones
     *     WebSocket.
     */
    public CatalogWebSocketConfig(
            AdminScraperNotifier scraperNotifier,
            @Value("${app.public-base-url}") String publicBaseUrl) {
        this.scraperNotifier = scraperNotifier;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Asocia el difusor con /api/v1/admin/scraper/ws bajo el origen
     * permitido.
     *
     * @param registry Registro de endpoints WebSocket del contexto Spring.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(scraperNotifier, "/api/v1/admin/scraper/ws").setAllowedOrigins(publicBaseUrl);
    }
}

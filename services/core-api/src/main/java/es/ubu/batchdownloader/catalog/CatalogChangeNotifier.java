package es.ubu.batchdownloader.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Informa por WebSocket de la versión actual y sus cambios sin permitir que una nueva conexión
 * consuma la invalidación de las existentes.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.CatalogStatisticsRepository
 * @see es.ubu.batchdownloader.catalog.CatalogWebSocketConfig
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Component
public class CatalogChangeNotifier extends TextWebSocketHandler {
    /**
     * Estado {@code catalog} mantenido por {@code CatalogChangeNotifier}.
     */
    private final CatalogRepository catalog;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code CatalogChangeNotifier}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code sessions} mantenido por {@code CatalogChangeNotifier}.
     */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /**
     * Estado {@code lastVersion} mantenido por {@code CatalogChangeNotifier}.
     */
    private volatile String lastVersion;

    /**
     * Conecta la versión compuesta del catálogo y la serialización de sus eventos públicos.
     *
     * @param catalog Fachada de consultas del catálogo que conserva filtros, proyecciones y
     *     versiones públicas.
     * @param objectMapper Conversor JSON de eventos públicos o respuestas del servicio semántico.
     */
    public CatalogChangeNotifier(CatalogRepository catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra la conexión y le envía el estado actual; solo la primera conexión inicializa la
     * versión compartida del sondeo.
     *
     * @param session Conexión WebSocket que recibe la versión actual y los cambios posteriores.
     * @throws Exception si no puede consultar, serializar o enviar la instantánea inicial.
     */
    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws Exception {
        boolean firstSession = sessions.isEmpty();
        sessions.add(session);
        var event = catalog.changeEvent();
        if (firstSession) {
            lastVersion = event.version();
        }
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
    }

    /**
     * Retira la conexión cerrada para que deje de participar en difusiones posteriores.
     *
     * @param session Conexión WebSocket que recibe la versión actual y los cambios posteriores.
     * @param status Motivo de cierre comunicado por el servidor WebSocket.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * Sondea solo cuando existen sesiones y difunde la versión si cambió; elimina conexiones
     * cerradas o fallidas sin impedir el envío a las demás.
     *
     * @throws Exception si falla la consulta o serialización del evento antes de difundirlo.
     */
    @Scheduled(fixedDelayString = "${app.catalog-events.poll-ms:3000}")
    public synchronized void publishIfChanged() throws Exception {
        if (sessions.isEmpty()) {
            return;
        }
        var event = catalog.changeEvent();
        if (event.version().equals(lastVersion)) {
            return;
        }
        lastVersion = event.version();
        TextMessage message = new TextMessage(objectMapper.writeValueAsString(event));
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (Exception exception) {
                sessions.remove(session);
            }
        }
    }
}

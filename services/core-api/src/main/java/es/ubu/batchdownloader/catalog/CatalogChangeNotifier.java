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
 * Implementa el componente {@code CatalogChangeNotifier}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Inicializa una instancia de {@code CatalogChangeNotifier}.
     *
     * @param catalog Acceso al catálogo utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     */
    public CatalogChangeNotifier(CatalogRepository catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    /**
     * Implementa {@code afterConnectionEstablished} para {@code CatalogChangeNotifier}.
     *
     * @param session Valor de {@code session} utilizado por la operación.
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
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
     * Implementa {@code afterConnectionClosed} para {@code CatalogChangeNotifier}.
     *
     * @param session Valor de {@code session} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * Publica el contenido solicitado mediante {@code publishIfChanged}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
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

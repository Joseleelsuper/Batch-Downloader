package es.ubu.batchdownloader.admin;

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
 * Implementa el componente {@code AdminScraperNotifier}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class AdminScraperNotifier extends TextWebSocketHandler {
    /**
     * Estado {@code scraper} mantenido por {@code AdminScraperNotifier}.
     */
    private final AdminScraperRepository scraper;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code AdminScraperNotifier}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code sessions} mantenido por {@code AdminScraperNotifier}.
     */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    /**
     * Estado {@code lastVersion} mantenido por {@code AdminScraperNotifier}.
     */
    private volatile String lastVersion;

    /**
     * Inicializa una instancia de {@code AdminScraperNotifier}.
     *
     * @param scraper Valor de {@code scraper} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     */
    public AdminScraperNotifier(AdminScraperRepository scraper, ObjectMapper objectMapper) {
        this.scraper = scraper;
        this.objectMapper = objectMapper;
    }

    /**
     * Implementa {@code afterConnectionEstablished} para {@code AdminScraperNotifier}.
     *
     * @param session Valor de {@code session} utilizado por la operación.
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        var event = scraper.event();
        lastVersion = event.version();
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
    }

    /**
     * Implementa {@code afterConnectionClosed} para {@code AdminScraperNotifier}.
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
    @Scheduled(fixedDelayString = "${app.scraper-events.poll-ms:2000}")
    public void publishIfChanged() throws Exception {
        if (sessions.isEmpty()) {
            return;
        }
        var event = scraper.event();
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

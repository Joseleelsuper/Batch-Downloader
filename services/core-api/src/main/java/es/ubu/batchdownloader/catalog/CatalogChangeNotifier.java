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

@Component
public class CatalogChangeNotifier extends TextWebSocketHandler {
    private final CatalogRepository catalog;
    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile String lastVersion;

    public CatalogChangeNotifier(CatalogRepository catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        var event = catalog.changeEvent();
        lastVersion = event.version();
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Scheduled(fixedDelayString = "${app.catalog-events.poll-ms:3000}")
    public void publishIfChanged() throws Exception {
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

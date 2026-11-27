package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.identity.infrastructure.security.AdminSessionVersion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Publica por WebSocket el estado administrativo del scraper al conectar y cuando cambia su
 * versión; retira conexiones cerradas o que fallan al enviar.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminScraperRepository
 * @see ScraperOperationsDtos.ScraperEvent
 * @since 0.1.0
 * @version 0.1.0
 * @category Operaciones administrativas
 */
@Component
public class AdminScraperNotifier extends TextWebSocketHandler {
    /**
     * Estado {@code scraper} mantenido por {@code AdminScraperNotifier}.
     */
    private final AdminScraperRepository scraper;
    private final AdminSessionVersion adminSessions;
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
     * Conecta la consulta del estado del scraper con la serialización de mensajes WebSocket.
     *
     * @param scraper Consulta de colas y versión del scraper.
     * @param objectMapper Serializador del evento WebSocket administrativo.
     */
    public AdminScraperNotifier(
            AdminScraperRepository scraper, ObjectMapper objectMapper, AdminSessionVersion adminSessions) {
        this.scraper = scraper;
        this.adminSessions = adminSessions;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra la conexión y le envía inmediatamente el estado actual, guardando la versión
     * utilizada para detectar cambios posteriores.
     *
     * @param session Conexión WebSocket ya aceptada por la configuración de acceso.
     * @throws java.lang.Exception si falla la consulta, la serialización o el primer envío.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!keepSession(session)) return;
        sessions.add(session);
        var event = scraper.event();
        lastVersion = event.version();
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
    }

    /**
     * Retira la conexión del conjunto de destinatarios de futuras actualizaciones.
     *
     * @param session Conexión WebSocket ya aceptada por la configuración de acceso.
     * @param status Motivo de cierre de la conexión; no modifica el estado persistido del scraper.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * Consulta el estado solo cuando hay destinatarios, omite versiones repetidas y distribuye un
     * único mensaje serializado; un fallo de envío retira únicamente esa conexión.
     *
     * @throws java.lang.Exception si falla la consulta o serialización antes de distribuir el
     *     evento.
     */
    @Scheduled(fixedDelayString = "${app.scraper-events.poll-ms:2000}")
    public void publishIfChanged() throws Exception {
        if (sessions.isEmpty()) {
            return;
        }
        var authorized = new ArrayList<WebSocketSession>();
        for (WebSocketSession session : sessions) {
            if (keepSession(session)) authorized.add(session);
        }
        if (authorized.isEmpty()) return;

        var event = scraper.event();
        if (event.version().equals(lastVersion)) return;
        lastVersion = event.version();
        TextMessage message = new TextMessage(objectMapper.writeValueAsString(event));
        for (WebSocketSession session : authorized) {
            try {
                session.sendMessage(message);
            } catch (Exception exception) {
                sessions.remove(session);
            }
        }
    }

    private boolean keepSession(WebSocketSession session) throws IOException {
        if (session.isOpen() && adminSessions.isCurrent(session.getPrincipal())) return true;
        sessions.remove(session);
        if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
        return false;
    }
}

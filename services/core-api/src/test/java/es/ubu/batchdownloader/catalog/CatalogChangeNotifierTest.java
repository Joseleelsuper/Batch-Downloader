package es.ubu.batchdownloader.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogChangeEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Agrupa los escenarios de prueba de {@code CatalogChangeNotifierTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class CatalogChangeNotifierTest {
    /**
     * Comprueba el escenario {@code
     * connectingASecondClientCannotConsumeAChangeForExistingClients}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void connectingASecondClientCannotConsumeAChangeForExistingClients() throws Exception {
        CatalogRepository catalog = mock(CatalogRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        WebSocketSession firstSession = mock(WebSocketSession.class);
        WebSocketSession secondSession = mock(WebSocketSession.class);
        CatalogChangeEvent versionA = new CatalogChangeEvent(
                "catalog.changed", "A", LocalDateTime.now());
        CatalogChangeEvent versionB = new CatalogChangeEvent(
                "catalog.changed", "B", LocalDateTime.now());
        when(catalog.changeEvent()).thenReturn(versionA, versionB, versionB);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"type\":\"catalog.changed\"}");
        when(firstSession.isOpen()).thenReturn(true);
        when(secondSession.isOpen()).thenReturn(true);
        CatalogChangeNotifier notifier = new CatalogChangeNotifier(catalog, objectMapper);

        notifier.afterConnectionEstablished(firstSession);
        notifier.afterConnectionEstablished(secondSession);
        notifier.publishIfChanged();

        verify(firstSession, times(2)).sendMessage(any(TextMessage.class));
        verify(secondSession, times(2)).sendMessage(any(TextMessage.class));
    }
}

package es.ubu.batchdownloader.downloadworker.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobFailedEvent;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;

/**
 * Verifica cuándo un fallo agotado vuelve a la cola y cuándo publica un resultado terminal antes de
 * rechazar el comando.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobFailureRecoverer
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de integración y mensajería
 */
class DownloadJobFailureRecovererTest {
    /**
     * Simula storage_busy y comprueba reencolado inmediato sin publicar un evento de fallo
     * terminal.
     */
    @Test
    void keepsStorageCapacityFailuresInTheInputQueue() {
        RecordingPublisher publisher = new RecordingPublisher();
        DownloadJobFailureRecoverer recoverer = new DownloadJobFailureRecoverer(
                new ObjectMapper(), publisher, Clock.systemUTC());

        assertThatThrownBy(() -> recoverer.recover(
                        new Message(new byte[0]),
                        new InfrastructureException("storage_busy", new IllegalStateException())))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
        assertThat(publisher.events).isEmpty();
    }

    /**
     * Agota un fallo de procesamiento y comprueba publicación de JOB_FAILED para el trabajo y
     * rechazo sin reencolado con el mismo código.
     */
    @Test
    void publishesATerminalFailureBeforeRejectingTheCommandToItsDlq() {
        UUID jobId = UUID.randomUUID();
        DownloadJobRequestedEvent requested = new DownloadJobRequestedEvent(
                UUID.randomUUID(),
                EventTypes.JOB_REQUESTED,
                EventTypes.CURRENT_VERSION,
                Instant.parse("2026-07-11T11:00:00Z"),
                UUID.randomUUID().toString(),
                null,
                new DownloadJobPayload(
                        jobId,
                        List.of(new DownloadItemRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID()))));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RecordingPublisher publisher = new RecordingPublisher();
        DownloadJobFailureRecoverer recoverer = new DownloadJobFailureRecoverer(
                mapper,
                publisher,
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> recoverer.recover(
                        new Message(mapper.writeValueAsBytes(requested)),
                        new IllegalStateException("transient failure")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("download_job_processing_failed");

        assertThat(publisher.routingKeys).containsExactly(EventTypes.JOB_FAILED_ROUTING_KEY);
        DownloadJobFailedEvent failed = (DownloadJobFailedEvent) publisher.events.getFirst();
        assertThat(failed.payload().jobId()).isEqualTo(jobId);
        assertThat(failed.payload().errorCode()).isEqualTo("download_job_processing_failed");
    }

    /**
     * Hace fallar la publicación del resultado terminal y comprueba que el comando se reencola
     * inmediatamente.
     */
    @Test
    void requeuesInsteadOfDeadLetteringWhenTheTerminalFailureCannotBePublished() {
        DownloadJobRequestedEvent requested = new DownloadJobRequestedEvent(
                UUID.randomUUID(),
                EventTypes.JOB_REQUESTED,
                EventTypes.CURRENT_VERSION,
                Instant.parse("2026-07-11T11:00:00Z"),
                UUID.randomUUID().toString(),
                null,
                new DownloadJobPayload(
                        UUID.randomUUID(),
                        List.of(new DownloadItemRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID()))));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DownloadJobFailureRecoverer recoverer = new DownloadJobFailureRecoverer(
                mapper,
                (routingKey, event) -> {
                    throw new IllegalStateException("event broker unavailable");
                },
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> recoverer.recover(
                        new Message(mapper.writeValueAsBytes(requested)),
                        new IllegalStateException("processing failure")))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    /**
     * Conserva claves y sobres de eventos en el orden recibido para comprobar resultados del
     * recuperador.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Pruebas de integración y mensajería
     */
    private static final class RecordingPublisher implements EventPublisher {
        /**
         * Dato compartido {@code routingKeys} para los escenarios de prueba.
         */
        private final List<String> routingKeys = new ArrayList<>();
        /**
         * Dato compartido {@code events} para los escenarios de prueba.
         */
        private final List<Object> events = new ArrayList<>();

        /**
         * Añade la clave y el sobre a los registros paralelos de la prueba.
         *
         * @param routingKey clave de enrutamiento que se captura para las aserciones.
         * @param event sobre del evento publicado por el recuperador.
         */
        @Override
        public void publish(String routingKey, Object event) {
            routingKeys.add(routingKey);
            events.add(event);
        }
    }
}

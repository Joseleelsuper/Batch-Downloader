package es.ubu.batchdownloader.notification.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.notification.config.RabbitTopologyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * Comprueba colas, enlaces y configuración de descartes de la topología de correo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitTopologyConfiguration
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class RabbitTopologyConfigurationTest {

    /**
     * Dato compartido {@code configuration} para los escenarios de prueba.
     */
    private RabbitTopologyConfiguration configuration;
    /**
     * Dato compartido {@code properties} para los escenarios de prueba.
     */
    private RabbitTopologyProperties properties;

    /**
     * Construye nombres de exchanges y colas aislados para comprobar sus declaraciones.
     */
    @BeforeEach
    void setUp() {
        configuration = new RabbitTopologyConfiguration();
        properties = new RabbitTopologyProperties(
                "batch.commands.v1",
                "notification.email.requested",
                "notification.email-requests.v1",
                "batch-downloader.dlx",
                "batch.commands.v1.notification.email.requested.dead",
                "notification.email-requests.dlq.v1");
    }

    /**
     * Comprueba que la cola de entrada declara el exchange y clave necesarios para conservar
     * entregas rechazadas.
     */
    @Test
    void configuresRetryExhaustionToReachTheDeadLetterQueue() {
        Queue queue = configuration.notificationQueue(properties);

        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "batch-downloader.dlx")
                .containsEntry(
                        "x-dead-letter-routing-key",
                        "batch.commands.v1.notification.email.requested.dead");
    }

    /**
     * Comprueba los enlaces de la solicitud canónica y de su cola de descartes.
     */
    @Test
    void bindsTheCanonicalCommandAndTheDeadLetterQueue() {
        Queue queue = configuration.notificationQueue(properties);
        TopicExchange exchange = configuration.notificationExchange(properties);
        Binding notificationBinding = configuration.notificationRequestedBinding(
                queue, exchange, properties);
        Queue deadQueue = configuration.notificationDeadLetterQueue(properties);
        DirectExchange deadExchange = configuration.notificationDeadLetterExchange(properties);
        Binding deadBinding = configuration.deadLetterBinding(deadQueue, deadExchange, properties);

        assertThat(notificationBinding.getRoutingKey())
                .isEqualTo("notification.email.requested");
        assertThat(deadBinding.getRoutingKey())
                .isEqualTo("batch.commands.v1.notification.email.requested.dead");
    }
}

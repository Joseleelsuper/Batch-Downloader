package es.ubu.batchdownloader.downloadworker.ports;

/**
 * Publica eventos del procesamiento para que Core actualice progreso y resultado de los trabajos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface EventPublisher {
    /**
     * Entrega el sobre al transporte de eventos usando la clave indicada; un fallo de publicación
     * se propaga al coordinador.
     *
     * @param routingKey Clave AMQP que selecciona los consumidores del evento.
     * @param event Sobre de evento del contrato compartido que se serializa para publicar.
     */
    void publish(String routingKey, Object event);
}

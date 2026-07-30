package es.ubu.batchdownloader.downloadworker.ports;

/**
 * Define el contrato de {@code EventPublisher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface EventPublisher {
    /**
     * Publica el contenido solicitado mediante {@code publish}.
     *
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     * @param event Evento que debe procesarse.
     */
    void publish(String routingKey, Object event);
}

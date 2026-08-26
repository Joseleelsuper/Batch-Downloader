package es.ubu.batchdownloader.downloadworker.ports;

import java.time.Duration;
import java.util.UUID;

/**
 * Define el contrato de {@code InboxRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface InboxRepository {
    /**
     * Ejecuta la operación {@code tryStart}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param lease Valor de {@code lease} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    boolean tryStart(UUID eventId, Duration lease);

    /**
     * Ejecuta la operación {@code complete}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     */
    void complete(UUID eventId);

    /**
     * Libera el recurso solicitado mediante {@code release}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     */
    void release(UUID eventId);
}

package es.ubu.batchdownloader.downloadworker.ports;

import java.time.Duration;
import java.util.UUID;

/**
 * Deduplica entregas de eventos mediante reservas temporales y confirmación de procesamiento.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface InboxRepository {
    /**
     * Intenta reservar un evento todavía no procesado o cuya reserva puede recuperarse según el
     * arrendamiento.
     *
     * @param eventId UUID estable del mensaje de entrada, conservado entre entregas duplicadas.
     * @param lease Duración de la reserva de procesamiento antes de permitir recuperación.
     * @return true si el llamador puede procesarlo; false si ya se procesó o está reservado.
     */
    boolean tryStart(UUID eventId, Duration lease);

    /**
     * Confirma que el evento reservado terminó de procesarse para que futuras entregas no lo
     * ejecuten otra vez.
     *
     * @param eventId UUID estable del mensaje de entrada, conservado entre entregas duplicadas.
     */
    void complete(UUID eventId);

    /**
     * Libera una reserva que no pudo completarse para permitir un reintento posterior.
     *
     * @param eventId UUID estable del mensaje de entrada, conservado entre entregas duplicadas.
     */
    void release(UUID eventId);
}

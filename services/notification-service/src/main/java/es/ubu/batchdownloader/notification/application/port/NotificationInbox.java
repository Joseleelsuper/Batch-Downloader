package es.ubu.batchdownloader.notification.application.port;

import java.util.UUID;

/**
 * Define el contrato de {@code NotificationInbox}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface NotificationInbox {

    /**
     * Reserva el elemento solicitado mediante {@code claim}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param eventType Valor de {@code eventType} utilizado por la operación.
     * @return Resultado producido por {@code claim}.
     */
    ClaimResult claim(UUID eventId, String eventType);

    /**
     * Marca el recurso solicitado mediante {@code markProcessed}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     */
    void markProcessed(UUID eventId);

    /**
     * Marca el recurso solicitado mediante {@code markFailed}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param error Valor de {@code error} utilizado por la operación.
     */
    void markFailed(UUID eventId, String error);

    /**
     * Enumera los valores admitidos por {@code ClaimResult}.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    enum ClaimResult {
        /**
         * Constante que define {@code ACQUIRED}.
         */
        ACQUIRED,
        /**
         * Constante que define {@code ALREADY_PROCESSED}.
         */
        ALREADY_PROCESSED,
        /**
         * Constante que define {@code BUSY}.
         */
        BUSY
    }
}

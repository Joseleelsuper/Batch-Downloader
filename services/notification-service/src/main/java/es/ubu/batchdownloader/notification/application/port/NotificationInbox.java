package es.ubu.batchdownloader.notification.application.port;

import java.util.UUID;

/**
 * Evita procesar simultáneamente el mismo evento y conserva el resultado de cada intento.
 *
 * Una reserva adquirida permite enviar; una confirmación previa permite ignorar la entrega
 * duplicada.
 * La reserva ocupada exige posponerla sin volver a enviar desde este consumidor.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.application.ProcessEmailNotification
 * @see es.ubu.batchdownloader.notification.infrastructure.persistence.JdbcNotificationInbox
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public interface NotificationInbox {

    /**
     * Intenta reservar el evento nuevo o recuperar un intento fallido o una reserva caducada.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @param eventType Tipo de evento almacenado junto a la reserva del inbox.
     * @return ACQUIRED si puede procesarse; ALREADY_PROCESSED si ya terminó; BUSY si otro intento
     *     mantiene la reserva.
     */
    ClaimResult claim(UUID eventId, String eventType);

    /**
     * Confirma el envío del evento reservado y evita que una entrega posterior vuelva a procesarlo.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @throws IllegalStateException si no existe una reserva en procesamiento que pueda
     *     confirmarse.
     */
    void markProcessed(UUID eventId);

    /**
     * Registra el fallo del intento y libera la reserva para que otra entrega pueda reintentarlo.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @param error Descripción del fallo persistido; puede estar vacía y se acota antes de
     *     guardarla.
     *
     * @throws IllegalStateException si el evento no se encuentra en procesamiento.
     */
    void markFailed(UUID eventId, String error);

    /**
     * Distingue permiso para enviar, entrega duplicada completada y reserva ocupada por otro
     * intento.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @see es.ubu.batchdownloader.notification.application.port.NotificationInbox
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    enum ClaimResult {
        /**
         * El consumidor ha reservado el evento y puede iniciar el envío.
         */
        ACQUIRED,
        /**
         * El inbox ya confirmó el evento; se ignora esta entrega sin volver a enviar.
         */
        ALREADY_PROCESSED,
        /**
         * Otro intento mantiene una reserva vigente; esta entrega debe posponerse.
         */
        BUSY
    }
}

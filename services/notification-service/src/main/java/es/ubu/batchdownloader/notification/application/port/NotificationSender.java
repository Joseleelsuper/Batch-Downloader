package es.ubu.batchdownloader.notification.application.port;

import es.ubu.batchdownloader.notification.domain.EmailNotification;

/**
 * Define el contrato de {@code NotificationSender}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface NotificationSender {

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     */
    void send(EmailNotification notification);
}

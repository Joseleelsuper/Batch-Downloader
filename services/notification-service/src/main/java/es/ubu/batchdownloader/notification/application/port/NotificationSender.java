package es.ubu.batchdownloader.notification.application.port;

import es.ubu.batchdownloader.notification.domain.EmailNotification;

/**
 * Entrega al proveedor un correo validado y comunica si el envío falló de forma permanente o
 * temporal.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.domain.EmailNotification
 * @see es.ubu.batchdownloader.notification.application.ProcessEmailNotification
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public interface NotificationSender {

    /**
     * Envía el correo con el proveedor que corresponda a su plantilla.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @throws es.ubu.batchdownloader.notification.application.PermanentNotificationException si el
     *     contenido o la configuración impiden un envío válido sin cambios.
     *
     * @throws es.ubu.batchdownloader.notification.application.RetryableNotificationException si el
     *     proveedor o el transporte admiten un nuevo intento.
     *
     * @see es.ubu.batchdownloader.notification.infrastructure.mail.RoutingNotificationSender
     */
    void send(EmailNotification notification);
}

package es.ubu.batchdownloader.notification.application;

import es.ubu.batchdownloader.notification.domain.EmailNotification;

/** Puerto de entrada para una notificación ya validada y mapeada. */
@FunctionalInterface
public interface NotificationHandler {

    /**
     * Entrega un evento validado y confirma su procesamiento sin reenviar eventos completados.
     *
     * @param notification evento con identidad estable para controlar su idempotencia
     * @throws NotificationProcessingException si la reserva está ocupada o falla el procesamiento
     * @throws PermanentNotificationException si el proveedor rechaza definitivamente el envío
     * @throws RetryableNotificationException si el proveedor solicita un nuevo intento
     * @see ProcessEmailNotification
     */
    void handle(EmailNotification notification);
}

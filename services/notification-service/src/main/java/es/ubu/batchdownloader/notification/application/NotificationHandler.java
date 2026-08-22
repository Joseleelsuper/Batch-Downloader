package es.ubu.batchdownloader.notification.application;

import es.ubu.batchdownloader.notification.domain.EmailNotification;

/** Puerto de entrada para una notificación ya validada y mapeada. */
@FunctionalInterface
public interface NotificationHandler {

    /** Procesa la notificación recibida. */
    void handle(EmailNotification notification);
}

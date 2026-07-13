package es.ubu.batchdownloader.notification.application.port;

import es.ubu.batchdownloader.notification.domain.EmailNotification;

public interface NotificationSender {

    void send(EmailNotification notification);
}

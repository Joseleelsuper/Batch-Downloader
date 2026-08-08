package es.ubu.batchdownloader.notification.application;

/** Error permanente que debe ir a DLQ sin reintentos. */
public class PermanentNotificationException extends RuntimeException {
    public PermanentNotificationException(String code) {
        super(code);
    }
}

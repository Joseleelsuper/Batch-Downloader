package es.ubu.batchdownloader.notification.infrastructure.messaging;

public class InvalidDownloadEventException extends RuntimeException {

    public InvalidDownloadEventException(String message) {
        super(message);
    }

    public InvalidDownloadEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

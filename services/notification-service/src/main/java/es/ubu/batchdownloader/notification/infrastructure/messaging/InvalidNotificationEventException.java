package es.ubu.batchdownloader.notification.infrastructure.messaging;

/** Rechaza una solicitud de correo que no cumple el contrato de entrada de RabbitMQ. */
public class InvalidNotificationEventException extends RuntimeException {
    public InvalidNotificationEventException(String message) {
        super(message);
    }

    public InvalidNotificationEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

package es.ubu.batchdownloader.notification.infrastructure.messaging;

/**
 * Rechaza una solicitud de correo que no cumple el contrato de entrada de RabbitMQ.
 *
 * Aunque conserva su nombre histórico, también cubre eventos de identidad: JSON mal formado,
 * campos desconocidos, versión o ruta incorrectas y parámetros inválidos. El listener no debe
 * agotar reintentos de envío con un mensaje que necesita ser corregido por el productor.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
 *
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitNotificationRequestedListener
 *
 * @see es.ubu.batchdownloader.notification.config.NotificationRetryConfiguration
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public class InvalidDownloadEventException extends RuntimeException {

    /**
     * Conserva el motivo de rechazo del evento y la causa de lectura o validación cuando está
     * disponible.
     *
     * @param message Descripción de la condición que impide completar el procesamiento.
     */
    public InvalidDownloadEventException(String message) {
        super(message);
    }

    /**
     * Conserva el motivo de rechazo del evento y la causa de lectura o validación cuando está
     * disponible.
     *
     * @param message Descripción de la condición que impide completar el procesamiento.
     * @param cause Causa original que debe conservarse para clasificar o diagnosticar el fallo.
     */
    public InvalidDownloadEventException(String message, Throwable cause) {
        super(message, cause);
    }
}

package es.ubu.batchdownloader.notification.application;

import java.time.Duration;

/**
 * Solicita repetir una entrega tras un fallo temporal del proveedor o del transporte.
 *
 * Puede conservar la causa del fallo o el intervalo Retry-After. La política de espera utiliza
 * ese intervalo, acotado entre un milisegundo y cinco minutos, o la espera exponencial configurada.
 *
 * @see es.ubu.batchdownloader.notification.config.RetryAfterBackOffPolicy
 * @see es.ubu.batchdownloader.notification.application.ProcessEmailNotification
 * @see es.ubu.batchdownloader.notification.application.PermanentNotificationException
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public class RetryableNotificationException extends RuntimeException {
    /**
     * Intervalo solicitado, o null cuando corresponde aplicar la espera exponencial.
     */
    private final Duration retryAfter;

    /**
     * Conserva el código y la causa o demora disponibles para decidir el siguiente intento.
     *
     * @param code Código estable del fallo, sin credenciales ni contenido sensible.
     * @param retryAfter Espera solicitada por el proveedor; null permite usar la política
     *     exponencial.
     */
    public RetryableNotificationException(String code, Duration retryAfter) {
        super(code);
        this.retryAfter = retryAfter;
    }

    /**
     * Conserva el código y la causa o demora disponibles para decidir el siguiente intento.
     *
     * @param code Código estable del fallo, sin credenciales ni contenido sensible.
     * @param cause Causa original que debe conservarse para clasificar o diagnosticar el fallo.
     */
    public RetryableNotificationException(String code, Throwable cause) {
        super(code, cause);
        this.retryAfter = null;
    }

    /**
     * Expone la demora enviada por el proveedor para esta entrega.
     *
     * @return intervalo solicitado, o null cuando corresponde aplicar la espera exponencial.
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}

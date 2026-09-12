package es.ubu.batchdownloader.notification.application;

/**
 * Señala un fallo de correo que repetir la misma entrega no puede corregir.
 *
 * La configuración del listener examina también las causas anidadas y evita reintentos para
 * esta clasificación: por ejemplo, una plantilla no admitida o una configuración incompleta.
 *
 * @see es.ubu.batchdownloader.notification.config.NotificationRetryConfiguration
 * @see es.ubu.batchdownloader.notification.application.ProcessEmailNotification
 * @see es.ubu.batchdownloader.notification.application.RetryableNotificationException
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public class PermanentNotificationException extends RuntimeException {
    /**
     * Conserva el código permanente para que el listener rechace la entrega sin agotar reintentos.
     *
     * @param code Código estable del fallo, sin credenciales ni contenido sensible.
     */
    public PermanentNotificationException(String code) {
        super(code);
    }
}

package es.ubu.batchdownloader.notification.application;

import java.time.Duration;

/** Error temporal de proveedor; no incluye cuerpos ni credenciales. */
public class RetryableNotificationException extends RuntimeException {
    private final Duration retryAfter;

    public RetryableNotificationException(String code, Duration retryAfter) {
        super(code);
        this.retryAfter = retryAfter;
    }

    public RetryableNotificationException(String code, Throwable cause) {
        super(code, cause);
        this.retryAfter = null;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}

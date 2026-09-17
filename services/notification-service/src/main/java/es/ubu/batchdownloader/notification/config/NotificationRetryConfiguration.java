package es.ubu.batchdownloader.notification.config;

import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidDownloadEventException;
import java.time.Duration;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * Configura los reintentos del consumidor de correo según la causa y la demora del fallo.
 *
 * Los mensajes inválidos, rechazos explícitos y fallos permanentes no se reintentan. Los demás
 * respetan el número máximo de intentos y Retry-After cuando está disponible.
 *
 * @see es.ubu.batchdownloader.notification.config.RetryAfterBackOffPolicy
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitNotificationRequestedListener
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Configuration
class NotificationRetryConfiguration {
    /**
     * Instala clasificación de excepciones y espera en los listeners Rabbit, conservando otros
     * destinos de retry.
     *
     * @param maxAttempts Máximo de intentos para fallos que admiten reintento.
     * @param initialInterval Espera inicial de la política exponencial.
     * @param multiplier Factor aplicado al intervalo entre intentos consecutivos.
     * @param maxInterval Límite de la espera exponencial entre intentos.
     * @return personalizador que actúa únicamente sobre listeners.
     */
    @Bean
    RabbitRetryTemplateCustomizer notificationRetryCustomizer(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts}") int maxAttempts,
            @Value("${spring.rabbitmq.listener.simple.retry.initial-interval}") Duration initialInterval,
            @Value("${spring.rabbitmq.listener.simple.retry.multiplier}") double multiplier,
            @Value("${spring.rabbitmq.listener.simple.retry.max-interval}") Duration maxInterval) {
        RetryPolicy retryable = new SimpleRetryPolicy(maxAttempts);
        RetryPolicy never = new NeverRetryPolicy();
        ExceptionClassifierRetryPolicy classifier = new ExceptionClassifierRetryPolicy();
        classifier.setExceptionClassifier(exception -> isPermanent(exception) ? never : retryable);
        Sleeper sleeper = Thread::sleep;
        RetryAfterBackOffPolicy backOff = new RetryAfterBackOffPolicy(
                initialInterval, multiplier, maxInterval, sleeper);
        return (target, template) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                template.setRetryPolicy(classifier);
                template.setBackOffPolicy(backOff);
            }
        };
    }

    /**
     * Recorre las causas para detectar mensajes inválidos, rechazos sin reencolado o fallos
     * permanentes.
     *
     * @param exception Fallo que se describe o cuya cadena de causas se examina.
     * @return true si alguna causa impide reintentar la entrega.
     */
    private static boolean isPermanent(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof PermanentNotificationException
                    || current instanceof InvalidDownloadEventException
                    || current instanceof AmqpRejectAndDontRequeueException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

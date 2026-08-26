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

/** Clasifica reintentos del consumidor sin reintentar eventos permanentes. */
@Configuration
class NotificationRetryConfiguration {
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

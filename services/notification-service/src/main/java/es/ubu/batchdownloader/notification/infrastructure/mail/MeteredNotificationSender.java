package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Mide duración y resultado del envío por plantilla sin etiquetar destinatarios ni identificadores.
 * Los errores del enrutador se propagan intactos y el envío funciona también sin registro de
 * métricas.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.RoutingNotificationSender
 * @see es.ubu.batchdownloader.notification.application.port.NotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
@Primary
public final class MeteredNotificationSender implements NotificationSender {
    private final RoutingNotificationSender delegate;
    private final Optional<MeterRegistry> registry;

    /**
     * Envuelve el enrutador de proveedores con instrumentación opcional.
     *
     * @param delegate Enrutador que realiza el envío y propaga sus fallos sin cambiar su
     *     clasificación.
     *
     * @param registry Registro opcional de métricas; su ausencia permite enviar sin
     *     instrumentación.
     */
    public MeteredNotificationSender(
            RoutingNotificationSender delegate,
            Optional<MeterRegistry> registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /**
     * Envía a través del enrutador y mide éxito o fallo en un bloque final que no sustituye la
     * excepción original.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     */
    @Override
    public void send(EmailNotification notification) {
        MeterRegistry meterRegistry = registry.orElse(null);
        if (meterRegistry == null) {
            delegate.send(notification);
            return;
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            delegate.send(notification);
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder("notification_send")
                    .tag("template", notification.template().name().toLowerCase(java.util.Locale.ROOT))
                    .tag("outcome", outcome)
                    .register(meterRegistry));
        }
    }
}

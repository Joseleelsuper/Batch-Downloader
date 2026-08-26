package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Registra el resultado del envío sin usar destinatarios ni identificadores como etiquetas. */
@Component
@Primary
public final class MeteredNotificationSender implements NotificationSender {
    private final RoutingNotificationSender delegate;
    private final Optional<MeterRegistry> registry;

    /** Inicializa el wrapper exterior del router SMTP/Resend. */
    public MeteredNotificationSender(
            RoutingNotificationSender delegate,
            Optional<MeterRegistry> registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    /** {@inheritDoc} */
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

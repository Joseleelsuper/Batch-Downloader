package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configura la base pública de enlaces de los correos de identidad.
 *
 * @param publicBaseUrl URI absoluta de la web pública desde la que se construyen enlaces al
 *     usuario.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ConfigurationProperties(prefix = "notification.mail")
public record MailTemplateProperties(URI publicBaseUrl) {

    /**
     * Exige una base pública absoluta.
     *
     * @param publicBaseUrl URI absoluta de la web pública desde la que se construyen enlaces al
     *     usuario.
     *
     * @throws IllegalArgumentException si la URI no es absoluta.
     * @throws NullPointerException si falta la zona o la base pública.
     */
    public MailTemplateProperties {
        publicBaseUrl = Objects.requireNonNull(
                publicBaseUrl, "notification.mail.public-base-url no puede ser null");
        if (!publicBaseUrl.isAbsolute()) {
            throw new IllegalArgumentException("notification.mail.public-base-url debe ser absoluta");
        }
    }

}

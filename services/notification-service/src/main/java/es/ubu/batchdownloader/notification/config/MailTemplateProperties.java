package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configura la base pública de enlaces y el recurso visual de los correos de identidad.
 *
 * @param publicBaseUrl URI absoluta de la web pública desde la que se construyen enlaces al
 *     usuario.
 * @param logoUrl URI absoluta del logo que se muestra en la cabecera del correo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ConfigurationProperties(prefix = "notification.mail")
public record MailTemplateProperties(URI publicBaseUrl, URI logoUrl) {

    private static final String DEFAULT_LOGO_PATH = "/assets/batch-downloader-logo.png";

    /** Conserva el constructor corto utilizado por pruebas e integraciones existentes. */
    public MailTemplateProperties(URI publicBaseUrl) {
        this(publicBaseUrl, null);
    }

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
        if (logoUrl == null) {
            logoUrl = publicBaseUrl.resolve(DEFAULT_LOGO_PATH);
        }
        if (!logoUrl.isAbsolute()) {
            throw new IllegalArgumentException("notification.mail.logo-url debe ser absoluta");
        }
        if ("https".equalsIgnoreCase(publicBaseUrl.getScheme())
                && !"https".equalsIgnoreCase(logoUrl.getScheme())) {
            throw new IllegalArgumentException("notification.mail.logo-url debe usar HTTPS");
        }
    }

}

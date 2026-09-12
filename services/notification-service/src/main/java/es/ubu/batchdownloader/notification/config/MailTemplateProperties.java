package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configura remitente, zona de presentación de fechas y base pública de enlaces de los correos.
 *
 * @param from Remitente configurado para el proveedor de correo.
 * @param zoneId Identificador de zona horaria usado para mostrar la caducidad en los mensajes.
 * @param publicBaseUrl URI absoluta de la web pública desde la que se construyen enlaces al
 *     usuario.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.SmtpNotificationSender
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ConfigurationProperties(prefix = "notification.mail")
public record MailTemplateProperties(String from, String zoneId, URI publicBaseUrl) {

    /**
     * Normaliza el remitente y exige zona horaria reconocida y base pública absoluta.
     *
     * @param from Remitente configurado para el proveedor de correo.
     * @param zoneId Identificador de zona horaria usado para mostrar la caducidad en los mensajes.
     * @param publicBaseUrl URI absoluta de la web pública desde la que se construyen enlaces al
     *     usuario.
     *
     * @throws IllegalArgumentException si el remitente está vacío o la URI no es absoluta.
     * @throws java.time.DateTimeException si la zona horaria no es válida.
     * @throws NullPointerException si falta la zona o la base pública.
     */
    public MailTemplateProperties {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("notification.mail.from no puede estar vacío");
        }
        from = from.strip();
        ZoneId.of(zoneId);
        publicBaseUrl = Objects.requireNonNull(
                publicBaseUrl, "notification.mail.public-base-url no puede ser null");
        if (!publicBaseUrl.isAbsolute()) {
            throw new IllegalArgumentException("notification.mail.public-base-url debe ser absoluta");
        }
    }

    /**
     * Resuelve la zona usada para mostrar al destinatario los instantes de caducidad.
     *
     * @return zona horaria configurada.
     */
    public ZoneId resolvedZoneId() {
        return ZoneId.of(zoneId);
    }
}

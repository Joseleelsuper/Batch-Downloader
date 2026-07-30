package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Representa los datos inmutables de {@code MailTemplateProperties}.
 *
 * @param from Valor de {@code from} incluido en el record.
 * @param zoneId Valor de {@code zoneId} incluido en el record.
 * @param publicBaseUrl Valor de {@code publicBaseUrl} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@ConfigurationProperties(prefix = "notification.mail")
public record MailTemplateProperties(String from, String zoneId, URI publicBaseUrl) {

    /**
     * Inicializa una instancia de {@code MailTemplateProperties}.
     *
     * @param from Valor de {@code from} utilizado por la operación.
     * @param zoneId Identificador de {@code zone} utilizado por la operación.
     * @param publicBaseUrl Dirección de {@code publicBase} que debe procesarse.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
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
     * Resuelve el recurso solicitado mediante {@code resolvedZoneId}.
     *
     * @return Resultado producido por {@code resolvedZoneId}.
     */
    public ZoneId resolvedZoneId() {
        return ZoneId.of(zoneId);
    }
}

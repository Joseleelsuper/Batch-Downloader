package es.ubu.batchdownloader.notification.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configura el API de envío de identidad, su remitente y sus tiempos máximos de conexión y
 * petición.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ConfigurationProperties(prefix = "notification.resend")
public record ResendProperties(
        URI baseUrl,
        String apiKey,
        String from,
        Duration connectTimeout,
        Duration requestTimeout) {
    /**
     * Exige un endpoint absoluto y tiempos positivos; conserva vacías las credenciales opcionales.
     *
     * @param baseUrl URI absoluta del API de Resend.
     * @param apiKey Credencial de Resend; vacía deja el proveedor sin habilitar.
     * @param from Remitente configurado para el proveedor de correo.
     * @param connectTimeout Tiempo máximo positivo para establecer la conexión HTTP.
     * @param requestTimeout Tiempo máximo positivo de una petición HTTP completa.
     * @throws NullPointerException si falta la URI base.
     * @throws IllegalArgumentException si el endpoint es relativo o alguno de los tiempos no es
     *     positivo.
     */
    public ResendProperties {
        baseUrl = Objects.requireNonNull(baseUrl, "notification.resend.base-url es obligatorio");
        if (!baseUrl.isAbsolute()) throw new IllegalArgumentException("resend_base_url_must_be_absolute");
        apiKey = optionalText(apiKey);
        from = optionalText(from);
        connectTimeout = requirePositive(connectTimeout, "notification.resend.connect-timeout");
        requestTimeout = requirePositive(requestTimeout, "notification.resend.request-timeout");
    }

    /**
     * Comprueba que existen credencial y remitente para intentar un envío con Resend.
     *
     * @return true cuando apiKey y from contienen texto.
     */
    public boolean enabled() {
        return !apiKey.isBlank() && !from.isBlank();
    }

    /**
     * Normaliza las opciones de texto que pueden deshabilitar el proveedor.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @return texto sin espacios exteriores; cadena vacía para null.
     */
    private static String optionalText(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * Exige un intervalo real para acotar las conexiones y peticiones HTTP.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @param name Propiedad del intervalo, utilizada para identificar una configuración inválida.
     * @return duración recibida sin modificar.
     * @throws IllegalArgumentException si falta la duración, es cero o negativa.
     */
    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " debe ser positivo");
        }
        return value;
    }
}

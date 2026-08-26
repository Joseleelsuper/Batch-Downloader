package es.ubu.batchdownloader.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Representa los datos inmutables de {@code EmailNotification}.
 *
 * @param eventId Valor de {@code eventId} incluido en el record.
 * @param occurredAt Valor de {@code occurredAt} incluido en el record.
 * @param correlationId Valor de {@code correlationId} incluido en el record.
 * @param causationId Valor de {@code causationId} incluido en el record.
 * @param recipient Valor de {@code recipient} incluido en el record.
 * @param template Valor de {@code template} incluido en el record.
 * @param parameters Valor de {@code parameters} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public record EmailNotification(
        UUID eventId,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String recipient,
        Template template,
        Map<String, Object> parameters) {

    /**
     * Constante que define {@code EVENT_TYPE}.
     */
    public static final String EVENT_TYPE = "notification.email.requested";
    /**
     * Constante que define {@code SCHEMA_VERSION}.
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Inicializa una instancia de {@code EmailNotification}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param occurredAt Valor de {@code occurredAt} utilizado por la operación.
     * @param correlationId Identificador de {@code correlation} utilizado por la operación.
     * @param causationId Identificador de {@code causation} utilizado por la operación.
     * @param recipient Valor de {@code recipient} utilizado por la operación.
     * @param template Valor de {@code template} utilizado por la operación.
     * @param parameters Valor de {@code parameters} utilizado por la operación.
     */
    public EmailNotification {
        eventId = Objects.requireNonNull(eventId, "eventId no puede ser null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt no puede ser null");
        correlationId = requireText(correlationId, "correlationId");
        recipient = requireText(recipient, "recipient");
        template = Objects.requireNonNull(template, "template no puede ser null");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters no puede ser null"));
    }

    /**
     * Ejecuta la operación {@code requiredParameter}.
     *
     * @param name Nombre del elemento sobre el que se actúa.
     * @return Resultado producido por {@code requiredParameter}.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    public String requiredParameter(String name) {
        Object value = parameters.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Falta el parámetro obligatorio " + name);
        }
        return value.toString().strip();
    }

    /**
     * Ejecuta la operación {@code eventType}.
     *
     * @return Resultado producido por {@code eventType}.
     */
    public String eventType() {
        return EVENT_TYPE;
    }

    /**
     * Enumera los valores admitidos por {@code Template}.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public enum Template {
        /**
         * Constante que define {@code EMAIL_VERIFICATION}.
         */
        EMAIL_VERIFICATION,
        /**
         * Constante que define {@code PASSWORD_RESET}.
         */
        PASSWORD_RESET,
        /**
         * Constante que define {@code DOWNLOAD_READY}.
         */
        DOWNLOAD_READY,
        /**
         * Constante que define {@code DOWNLOAD_FAILED}.
         */
        DOWNLOAD_FAILED
    }

    /**
     * Ejecuta la operación {@code requireText}.
     *
     * @param value Valor que debe procesarse.
     * @param fieldName Valor de {@code fieldName} utilizado por la operación.
     * @return Resultado producido por {@code requireText}.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " no puede estar vacío");
        }
        return value.strip();
    }
}

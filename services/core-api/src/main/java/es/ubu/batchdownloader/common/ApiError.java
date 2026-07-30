package es.ubu.batchdownloader.common;

import java.util.Map;

/**
 * Representa los datos inmutables de {@code ApiError}.
 *
 * @param code Valor de {@code code} incluido en el record.
 * @param message Valor de {@code message} incluido en el record.
 * @param details Valor de {@code details} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public record ApiError(String code, String message, Map<String, Object> details) {
    /**
     * Ejecuta la operación {@code of}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @param message Mensaje que debe procesarse.
     * @return Resultado producido por {@code of}.
     */
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Map.of());
    }
}

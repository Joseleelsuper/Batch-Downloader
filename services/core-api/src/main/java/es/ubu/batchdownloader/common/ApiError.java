package es.ubu.batchdownloader.common;

import java.util.Map;

/**
 * Unifica los fallos HTTP mediante un código estable, un mensaje seguro y detalles estructurados
 * aptos para el cliente.
 *
 * @param code Código estable y seguro que permite al cliente identificar el fallo.
 * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
 * @param details Información estructurada y segura, como errores de campos; un mapa vacío indica
 *     ausencia de detalles.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @see es.ubu.batchdownloader.common.ApiErrorController
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public record ApiError(String code, String message, Map<String, Object> details) {
    /**
     * Construye un error sin detalles adicionales cuando bastan código y mensaje.
     *
     * @param code Código estable y seguro que permite al cliente identificar el fallo.
     * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
     * @return respuesta de error con mapa de detalles vacío.
     */
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Map.of());
    }
}

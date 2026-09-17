package es.ubu.batchdownloader.common;

/**
 * Distingue un permiso que ya caducó o fue consumido de uno desconocido, con respuesta HTTP 410.
 *
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public class GoneException extends RuntimeException {
    /**
     * Código estable y seguro que permite al cliente identificar el fallo.
     */
    private final String code;

    /**
     * Conserva el código funcional y el mensaje seguro del fallo.
     *
     * @param code Código estable y seguro que permite al cliente identificar el fallo.
     * @param message Explicación del fallo apta para mostrarse al usuario, sin detalles sensibles.
     */
    public GoneException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Identifica el fallo para que el cliente decida cómo presentarlo o recuperarse.
     *
     * @return código funcional estable.
     */
    public String code() { return code; }
}

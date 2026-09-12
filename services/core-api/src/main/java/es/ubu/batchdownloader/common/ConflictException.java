package es.ubu.batchdownloader.common;

/**
 * Señala que el estado actual del recurso impide aplicar una solicitud válida, para comunicar un
 * conflicto HTTP 409.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public class ConflictException extends RuntimeException {
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
    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Identifica el fallo para que el cliente decida cómo presentarlo o recuperarse.
     *
     * @return código funcional estable.
     */
    public String code() {
        return code;
    }
}

package es.ubu.batchdownloader.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sustituye la página de error del contenedor por JSON seguro para fallos que no resolvió un
 * controlador de la API.
 *
 * @see es.ubu.batchdownloader.common.ApiError
 * @see es.ubu.batchdownloader.common.ApiExceptionHandler
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
@RestController
public class ApiErrorController implements ErrorController {
    private static final String GENERIC_CODE = "unexpected_error";

    /**
     * Conserva el estado HTTP conocido y distingue recurso ausente; solo adjunta una ruta absoluta
     * de hasta 512 caracteres.
     *
     * @param request Solicitud HTTP fallida o petición interna según la firma del método.
     * @return respuesta JSON sin trazas ni mensajes internos del contenedor.
     */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        String path = safePath(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        String code = status == HttpStatus.NOT_FOUND ? "not_found" : GENERIC_CODE;
        String message = status == HttpStatus.NOT_FOUND
                ? "El recurso solicitado no existe"
                : "No se pudo completar la solicitud";
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, path == null ? Map.of() : Map.of("path", path)));
    }

    /**
     * Interpreta un código HTTP numérico conocido y utiliza 500 para valores ausentes o no
     * reconocidos.
     *
     * @param value Valor que se normaliza o comprueba según el contrato del método.
     * @return estado HTTP que se enviará al cliente.
     */
    private static HttpStatus resolveStatus(Object value) {
        if (value instanceof Number number) {
            HttpStatus status = HttpStatus.resolve(number.intValue());
            if (status != null) return status;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Acepta únicamente una cadena que empieza por barra y no supera 512 caracteres.
     *
     * @param value Valor que se normaliza o comprueba según el contrato del método.
     * @return ruta aceptada o null si no cumple esas condiciones.
     */
    private static String safePath(Object value) {
        if (!(value instanceof String path) || !path.startsWith("/") || path.length() > 512) return null;
        return path;
    }
}

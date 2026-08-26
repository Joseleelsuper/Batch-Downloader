package es.ubu.batchdownloader.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sustituye la página Whitelabel por un contrato JSON seguro para errores de la API. */
@RestController
public class ApiErrorController implements ErrorController {
    private static final String GENERIC_CODE = "unexpected_error";

    /**
     * Responde al despacho interno de errores sin exponer excepciones ni mensajes del servidor.
     *
     * @param request Petición que contiene los atributos del error original.
     * @return Error normalizado con el estado HTTP original.
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

    private static HttpStatus resolveStatus(Object value) {
        if (value instanceof Number number) {
            HttpStatus status = HttpStatus.resolve(number.intValue());
            if (status != null) return status;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String safePath(Object value) {
        if (!(value instanceof String path) || !path.startsWith("/") || path.length() > 512) return null;
        return path;
    }
}

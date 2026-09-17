package es.ubu.batchdownloader.common;

import java.util.Map;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce errores de aplicación y Spring a contratos HTTP seguros, conservando códigos funcionales
 * y plazos de reintento cuando existen.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.common.ApiError
 * @see es.ubu.batchdownloader.common.ServiceUnavailableException
 * @see es.ubu.batchdownloader.common.RateLimitException
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * Estado {@code log} mantenido por {@code ApiExceptionHandler}.
     */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Traslada el código y mensaje funcionales a HTTP 404.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 404 con el contrato ApiError.
     */
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Conserva el 404 estándar de Spring cuando la petición no coincide con ningún controlador.
     *
     * @param exception Fallo generado por el manejador de recursos ante una ruta inexistente.
     * @return respuesta HTTP 404 con el contrato ApiError.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missingResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "El recurso solicitado no existe"));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 409.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 409 con el contrato ApiError.
     */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 400.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 400 con el contrato ApiError.
     */
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> badRequest(BadRequestException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 410.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 410 con el contrato ApiError.
     */
    @ExceptionHandler(GoneException.class)
    ResponseEntity<ApiError> gone(GoneException exception) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 403.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 403 con el contrato ApiError.
     */
    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiError> forbiddenCode(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 422.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 422 con el contrato ApiError.
     */
    @ExceptionHandler(UnprocessableEntityException.class)
    ResponseEntity<ApiError> unprocessable(UnprocessableEntityException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 503 y conserva Retry-After.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 503 con el contrato ApiError.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ApiError> unavailable(ServiceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.retryAfterSeconds()))
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 429 y conserva Retry-After.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 429 con el contrato ApiError.
     */
    @ExceptionHandler(RateLimitException.class)
    ResponseEntity<ApiError> rateLimited(RateLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.retryAfterSeconds()))
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Registra el fallo de conexión o creación de transacción y lo presenta como indisponibilidad
     * temporal sin exponer su causa.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 503 service_busy con Retry-After de un segundo.
     */
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        CannotCreateTransactionException.class
    })
    ResponseEntity<ApiError> databaseUnavailable(RuntimeException exception) {
        log.warn("Database connection unavailable: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.of("service_busy", "El servicio está ocupado. Inténtalo de nuevo."));
    }

    /**
     * Convierte un fallo interno del proveedor de autenticación en indisponibilidad temporal del
     * servicio.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 503 service_busy con Retry-After de un segundo.
     */
    @ExceptionHandler(AuthenticationServiceException.class)
    ResponseEntity<ApiError> authenticationServiceUnavailable(AuthenticationServiceException exception) {
        log.warn("Authentication service unavailable: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.of("service_busy", "El servicio está ocupado. Inténtalo de nuevo."));
    }

    /**
     * Convierte una violación de clave única no clasificada en conflicto de recurso existente.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 409 duplicate_resource sin detalles SQL.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("duplicate_resource", "El recurso ya existe."));
    }

    /**
     * Presenta la imposibilidad de adquirir un bloqueo como conflicto con otra operación de base de
     * datos, sin exponer detalles SQL.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 409 database_busy con un mensaje que permite volver a intentar la operación.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<ApiError> databaseBusy(CannotAcquireLockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "database_busy",
                        "La base de datos está procesando otra operación. Inténtalo de nuevo."));
    }

    /**
     * Oculta los detalles del analizador cuando el cuerpo HTTP no puede convertirse al tipo
     * esperado.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 400 invalid_json.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_json", "El cuerpo de la solicitud no contiene JSON válido."));
    }

    /**
     * Agrupa los mensajes de validación por nombre de campo conservando su orden de encuentro.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 400 validation_failed con detalles fieldErrors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        "validation_failed",
                        "La solicitud contiene datos invalidos.",
                        Map.of("fieldErrors", exception.getBindingResult().getFieldErrors().stream()
                                .collect(java.util.stream.Collectors.groupingBy(
                                        org.springframework.validation.FieldError::getField,
                                        java.util.LinkedHashMap::new,
                                        java.util.stream.Collectors.mapping(
                                                org.springframework.validation.FieldError::getDefaultMessage,
                                                java.util.stream.Collectors.toList()))))));
    }

    /**
     * Convierte la denegación de Spring Security en un mensaje seguro de permisos insuficientes.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 403 forbidden.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "No tienes permisos para realizar esta operacion."));
    }

    /**
     * Unifica los fallos de autenticación de Spring sin identificar la credencial o condición
     * concreta que falló.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 401 invalid_credentials.
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("invalid_credentials", "Credenciales incorrectas o cuenta sin verificar."));
    }

    /**
     * Traslada el código y mensaje funcionales a HTTP 401.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return respuesta HTTP 401 con el contrato ApiError.
     */
    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiError> unauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Registra a nivel debug el cierre del cliente y evita intentar escribir otra respuesta en una
     * conexión inutilizable.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientDisconnected(AsyncRequestNotUsableException exception) {
        log.debug("Client disconnected before the response completed: {}", exception.getMessage());
    }

    /**
     * Registra la causa completa para diagnóstico interno y devuelve un mensaje genérico al
     * cliente.
     *
     * @param exception Fallo recibido por la frontera HTTP que debe convertirse en una respuesta
     *     segura.
     * @return 500 internal_error sin detalles de la excepción.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unexpected API error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "Se produjo un error interno."));
    }
}

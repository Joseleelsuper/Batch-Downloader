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

/**
 * Implementa el componente {@code ApiExceptionHandler}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * Estado {@code log} mantenido por {@code ApiExceptionHandler}.
     */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Ejecuta la operación {@code notFound}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code notFound}.
     */
    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Ejecuta la operación {@code conflict}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code conflict}.
     */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Ejecuta la operación {@code badRequest}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code badRequest}.
     */
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> badRequest(BadRequestException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(GoneException.class)
    ResponseEntity<ApiError> gone(GoneException exception) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ApiError> forbiddenCode(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Ejecuta la operación {@code unprocessable}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code unprocessable}.
     */
    @ExceptionHandler(UnprocessableEntityException.class)
    ResponseEntity<ApiError> unprocessable(UnprocessableEntityException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Ejecuta la operación {@code unavailable}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code unavailable}.
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ApiError> unavailable(ServiceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.retryAfterSeconds()))
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Ejecuta la operación {@code rateLimited}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code rateLimited}.
     */
    @ExceptionHandler(RateLimitException.class)
    ResponseEntity<ApiError> rateLimited(RateLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.retryAfterSeconds()))
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Convierte el agotamiento del pool en una respuesta rápida y reintentable.
     *
     * @param exception Error de adquisición de conexión.
     * @return Respuesta temporal de capacidad.
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
     * Evita convertir el agotamiento de base de datos durante el login en credenciales inválidas.
     *
     * @param exception Fallo interno del proveedor de autenticación.
     * @return Respuesta temporal de capacidad.
     */
    @ExceptionHandler(AuthenticationServiceException.class)
    ResponseEntity<ApiError> authenticationServiceUnavailable(AuthenticationServiceException exception) {
        log.warn("Authentication service unavailable: {}", exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.of("service_busy", "El servicio está ocupado. Inténtalo de nuevo."));
    }

    /**
     * Ejecuta la operación {@code duplicate}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code duplicate}.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("duplicate_resource", "El recurso ya existe."));
    }

    /**
     * Ejecuta la operación {@code databaseBusy}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code databaseBusy}.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<ApiError> databaseBusy(CannotAcquireLockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        "database_busy",
                        "La base de datos está procesando otra operación. Inténtalo de nuevo."));
    }

    /**
     * Ejecuta la operación {@code invalidJson}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code invalidJson}.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_json", "El cuerpo de la solicitud no contiene JSON válido."));
    }

    /**
     * Ejecuta la operación {@code validation}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code validation}.
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
     * Ejecuta la operación {@code forbidden}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code forbidden}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "No tienes permisos para realizar esta operacion."));
    }

    /**
     * Ejecuta la operación {@code authentication}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code authentication}.
     */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of("invalid_credentials", "Credenciales incorrectas o cuenta sin verificar."));
    }

    /**
     * Ejecuta la operación {@code unauthorized}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code unauthorized}.
     */
    @ExceptionHandler(UnauthorizedException.class)
    ResponseEntity<ApiError> unauthorized(UnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(exception.code(), exception.getMessage()));
    }

    /**
     * Ejecuta la operación {@code clientDisconnected}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientDisconnected(AsyncRequestNotUsableException exception) {
        log.debug("Client disconnected before the response completed: {}", exception.getMessage());
    }

    /**
     * Ejecuta la operación {@code unexpected}.
     *
     * @param exception Valor de {@code exception} utilizado por la operación.
     * @return Resultado producido por {@code unexpected}.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unexpected API error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "Se produjo un error interno."));
    }
}

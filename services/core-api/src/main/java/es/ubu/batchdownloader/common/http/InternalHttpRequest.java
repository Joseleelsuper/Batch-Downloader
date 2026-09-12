package es.ubu.batchdownloader.common.http;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Conserva una petición interna inmutable y etiquetas de observación estables para que cada
 * política pueda añadir cabeceras o plazo sin alterar la original.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public final class InternalHttpRequest {
    /**
     * Nombre estable del servicio destino utilizado como dimensión de métricas.
     */
    private final String service;
    /**
     * Nombre estable de la operación; evita usar URLs o identificadores variables en métricas.
     */
    private final String operation;
    /**
     * Verbo HTTP explícito de la solicitud.
     */
    private final String method;
    /**
     * URI interna completa del recurso de destino.
     */
    private final URI uri;
    /**
     * Cuerpo textual de la petición o respuesta; null en una petición significa ausencia de cuerpo.
     */
    private final String body;
    /**
     * Plazo máximo de ejecución HTTP; null mantiene el comportamiento del cliente subyacente.
     */
    private final Duration timeout;
    /**
     * Cabeceras HTTP; las peticiones conservan una copia inmutable.
     */
    private final Map<String, String> headers;

    /**
     * Conserva servicio, operación, verbo y URI no nulos; copia cabeceras y permite cuerpo o plazo
     * ausentes.
     *
     * @param service Nombre estable del servicio destino utilizado como dimensión de métricas.
     * @param operation Nombre estable de la operación; evita usar URLs o identificadores variables
     *     en métricas.
     * @param method Verbo HTTP explícito de la solicitud.
     * @param uri URI interna completa del recurso de destino.
     * @param body Cuerpo textual de la petición o respuesta; null en una petición significa
     *     ausencia de cuerpo.
     */
    public InternalHttpRequest(
            String service,
            String operation,
            String method,
            URI uri,
            String body) {
        this(service, operation, method, uri, body, null, Map.of());
    }

    /**
     * Conserva servicio, operación, verbo y URI no nulos; copia cabeceras y permite cuerpo o plazo
     * ausentes.
     *
     * @param service Nombre estable del servicio destino utilizado como dimensión de métricas.
     * @param operation Nombre estable de la operación; evita usar URLs o identificadores variables
     *     en métricas.
     * @param method Verbo HTTP explícito de la solicitud.
     * @param uri URI interna completa del recurso de destino.
     * @param body Cuerpo textual de la petición o respuesta; null en una petición significa
     *     ausencia de cuerpo.
     * @param timeout Plazo máximo de ejecución HTTP; null mantiene el comportamiento del cliente
     *     subyacente.
     * @param headers Cabeceras HTTP; las peticiones conservan una copia inmutable.
     */
    private InternalHttpRequest(
            String service,
            String operation,
            String method,
            URI uri,
            String body,
            Duration timeout,
            Map<String, String> headers) {
        this.service = Objects.requireNonNull(service);
        this.operation = Objects.requireNonNull(operation);
        this.method = Objects.requireNonNull(method);
        this.uri = Objects.requireNonNull(uri);
        this.body = body;
        this.timeout = timeout;
        this.headers = Map.copyOf(headers);
    }

    /**
     * Crea otra petición con una cabecera añadida o sustituida y conserva el resto de sus datos.
     *
     * @param name Nombre de cabecera o variable de estado según la operación.
     * @param value Valor de cabecera; no se admite null en la copia inmutable.
     * @return petición nueva; la original no cambia.
     */
    public InternalHttpRequest withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new InternalHttpRequest(
                service, operation, method, uri, body, timeout, copy);
    }

    /**
     * Crea otra petición con el plazo indicado y conserva contenido, identidad y cabeceras.
     *
     * @param value Nuevo plazo de la solicitud o null para no imponer uno.
     * @return petición nueva con el plazo solicitado.
     */
    public InternalHttpRequest withTimeout(Duration value) {
        return new InternalHttpRequest(
                service, operation, method, uri, body, value, headers);
    }

    /**
     * Nombre estable del servicio destino utilizado como dimensión de métricas.
     *
     * @return Nombre estable del servicio destino utilizado como dimensión de métricas.
     */
    public String service() {
        return service;
    }

    /**
     * Nombre estable de la operación; evita usar URLs o identificadores variables en métricas.
     *
     * @return Nombre estable de la operación; evita usar URLs o identificadores variables en
     *     métricas.
     */
    public String operation() {
        return operation;
    }

    /**
     * Verbo HTTP explícito de la solicitud.
     *
     * @return Verbo HTTP explícito de la solicitud.
     */
    public String method() {
        return method;
    }

    /**
     * URI interna completa del recurso de destino.
     *
     * @return URI interna completa del recurso de destino.
     */
    public URI uri() {
        return uri;
    }

    /**
     * Cuerpo textual de la petición o respuesta; null en una petición significa ausencia de cuerpo.
     *
     * @return Cuerpo textual de la petición o respuesta; null en una petición significa ausencia de
     *     cuerpo.
     */
    public String body() {
        return body;
    }

    /**
     * Plazo máximo de ejecución HTTP; null mantiene el comportamiento del cliente subyacente.
     *
     * @return Plazo máximo de ejecución HTTP; null mantiene el comportamiento del cliente
     *     subyacente.
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Cabeceras HTTP; las peticiones conservan una copia inmutable.
     *
     * @return Cabeceras HTTP; las peticiones conservan una copia inmutable.
     */
    public Map<String, String> headers() {
        return headers;
    }
}

package es.ubu.batchdownloader.common.http;

import java.net.http.HttpHeaders;

/**
 * Transporta el estado, las cabeceras y el cuerpo recibido para que el cliente de cada servicio
 * clasifique su resultado.
 *
 * @param statusCode Código de estado HTTP recibido del servicio remoto.
 * @param headers Cabeceras HTTP; las peticiones conservan una copia inmutable.
 * @param body Cuerpo textual de la petición o respuesta; null en una petición significa ausencia de
 *     cuerpo.
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
public record InternalHttpResponse(int statusCode, HttpHeaders headers, String body) {}

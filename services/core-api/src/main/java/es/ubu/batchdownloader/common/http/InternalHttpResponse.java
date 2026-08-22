package es.ubu.batchdownloader.common.http;

import java.net.http.HttpHeaders;

/** Respuesta HTTP interna independiente del cliente JDK concreto. */
public record InternalHttpResponse(int statusCode, HttpHeaders headers, String body) {}

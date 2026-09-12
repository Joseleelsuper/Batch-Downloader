package es.ubu.batchdownloader.common.http;

/**
 * Permite componer transporte HTTP interno, autenticación, plazo y métricas mediante un único
 * contrato de petición y respuesta.
 *
 * @see es.ubu.batchdownloader.common.http.InternalHttpRequest
 * @see es.ubu.batchdownloader.common.http.InternalHttpResponse
 * @since 0.1.0
 * @version 0.1.0
 * @category Infraestructura de Core
 */
@FunctionalInterface
public interface InternalHttpExecutor {

    /**
     * Ejecuta la petición según las políticas del colaborador sin interpretar errores de negocio
     * del destino.
     *
     * @param request Solicitud HTTP fallida o petición interna según la firma del método.
     * @return estado, cabeceras y cuerpo de la respuesta.
     * @throws es.ubu.batchdownloader.common.http.InternalHttpTransportException si la operación
     *     falla antes de obtener una respuesta HTTP.
     */
    InternalHttpResponse execute(InternalHttpRequest request);
}

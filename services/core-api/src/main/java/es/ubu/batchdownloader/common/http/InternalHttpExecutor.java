package es.ubu.batchdownloader.common.http;

/** Puerto común para ejecutar una única petición entre servicios. */
@FunctionalInterface
public interface InternalHttpExecutor {

    /**
     * Ejecuta la petición ya descrita por el cliente funcional.
     *
     * @param request petición interna.
     * @return respuesta materializada y acotada por el servidor interno.
     */
    InternalHttpResponse execute(InternalHttpRequest request);
}

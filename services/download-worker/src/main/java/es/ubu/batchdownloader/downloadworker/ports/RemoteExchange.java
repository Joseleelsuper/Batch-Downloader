package es.ubu.batchdownloader.downloadworker.ports;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;

/**
 * Abre una única respuesta HTTP remota sin seguir redirecciones.
 *
 * <p>La separación por salto permite aplicar la política de URL pública inmediatamente antes
 * de cada acceso de red.</p>
 */
public interface RemoteExchange {

    /**
     * Ejecuta un único GET sobre la URI indicada.
     *
     * @param uri destino que se debe consultar.
     * @return respuesta cuyo cuerpo debe cerrarse.
     */
    Response get(URI uri);

    /** Respuesta de streaming obtenida para un único salto. */
    interface Response extends AutoCloseable {

        /** @return estado HTTP recibido. */
        int statusCode();

        /** @return cabeceras HTTP recibidas. */
        HttpHeaders headers();

        /** @return cuerpo sin materializar de la respuesta. */
        InputStream body();

        /** Cierra siempre el cuerpo de la respuesta. */
        @Override
        default void close() throws IOException {
            body().close();
        }
    }
}

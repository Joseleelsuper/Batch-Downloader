package es.ubu.batchdownloader.downloadworker.ports;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;

/**
 * Aísla una lectura HTTP y la vida de su respuesta para que las políticas de descarga inspeccionen
 * cabeceras y consuman el cuerpo por streaming.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @see RemoteExchange.Response
 * @since 0.1.0
 * @version 0.1.0
 * @category Puertos del worker
 */
public interface RemoteExchange {

    /**
     * Abre una respuesta GET cuyo cuerpo debe cerrarse después de consumirlo o rechazarlo.
     *
     * @param uri URI de destino que debe superar la política de acceso a recursos públicos.
     * @return respuesta con estado, cabeceras y flujo de contenido.
     */
    Response get(URI uri);

    /**
     * Mantiene los metadatos y el flujo de una respuesta HTTP hasta que el consumidor la cierra.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Puertos del worker
     */
    interface Response extends AutoCloseable {

        /**
         * Expone el estado HTTP antes de decidir si se transfiere el contenido.
         *
         * @return código de estado recibido.
         */
        int statusCode();

        /**
         * Expone cabeceras para validar tamaño, formato, redirección u otras condiciones de la
         * descarga.
         *
         * @return cabeceras de la respuesta HTTP.
         */
        HttpHeaders headers();

        /**
         * Proporciona el flujo de contenido que se consume progresivamente.
         *
         * @return flujo abierto asociado a la respuesta.
         */
        InputStream body();

        /**
         * Cierra el flujo de contenido para liberar los recursos de la respuesta.
         *
         * @throws java.io.IOException si falla el cierre del cuerpo.
         */
        @Override
        default void close() throws IOException {
            body().close();
        }
    }
}

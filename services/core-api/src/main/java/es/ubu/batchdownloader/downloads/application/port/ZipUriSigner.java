package es.ubu.batchdownloader.downloads.application.port;

import java.net.URI;
import java.time.Duration;

/**
 * Concede lectura temporal de un ZIP publicado mediante una URI que el navegador puede utilizar
 * directamente.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public interface ZipUriSigner {
    /**
     * Firma el acceso de lectura al objeto durante la vigencia indicada.
     *
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param validity Vigencia solicitada para el permiso temporal de lectura.
     * @return URI temporal del almacén; no implica que el objeto exista.
     */
    URI signGet(String objectKey, Duration validity);

    /**
     * Permite sugerir un nombre de archivo al firmar la lectura; el método por defecto delega sin
     * utilizar ese nombre.
     *
     * @param objectKey Clave interna del ZIP en el almacén de objetos, nunca una URL firmada.
     * @param filename Nombre sugerido al navegador para guardar el ZIP.
     * @param validity Vigencia solicitada para el permiso temporal de lectura.
     * @return URI temporal de lectura del ZIP.
     */
    default URI signGet(String objectKey, String filename, Duration validity) {
        return signGet(objectKey, validity);
    }
}

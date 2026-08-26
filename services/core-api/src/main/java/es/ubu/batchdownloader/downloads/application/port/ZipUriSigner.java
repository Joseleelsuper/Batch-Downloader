package es.ubu.batchdownloader.downloads.application.port;

import java.net.URI;
import java.time.Duration;

/**
 * Define el contrato de {@code ZipUriSigner}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public interface ZipUriSigner {
    /**
     * Ejecuta la operación {@code signGet}.
     *
     * @param objectKey Valor de {@code objectKey} utilizado por la operación.
     * @param validity Valor de {@code validity} utilizado por la operación.
     * @return Resultado producido por {@code signGet}.
     */
    URI signGet(String objectKey, Duration validity);

    /**
     * Firma una descarga con un nombre de fichero explícito. La implementación por defecto
     * conserva compatibilidad con firmantes existentes.
     */
    default URI signGet(String objectKey, String filename, Duration validity) {
        return signGet(objectKey, validity);
    }
}

package es.ubu.batchdownloader.translation.infrastructure.file;

/**
 * Impide publicar un catálogo de traducciones ilegible, incoherente o con claves duplicadas.
 * Conserva la causa y la página implicada para diagnosticar el fallo durante el arranque, antes
 * de servir un documento incompleto al frontend.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.translation.infrastructure.file.JsonFileLocaleCatalog
 * @since 0.1.0
 * @version 0.1.0
 * @category Traducciones
 */
public class LocaleCatalogConfigurationException extends RuntimeException {

    /**
     * Conserva el motivo de rechazo del catálogo y el fallo de E/S o JSON cuando está disponible.
     *
     * @param message Descripción de la página, clave o condición que impide publicar el catálogo.
     */
    public LocaleCatalogConfigurationException(String message) {
        super(message);
    }

    /**
     * Conserva el motivo de rechazo del catálogo y el fallo de E/S o JSON cuando está disponible.
     *
     * @param message Descripción de la página, clave o condición que impide publicar el catálogo.
     * @param cause Error de lectura o serialización que originó el fallo de configuración.
     */
    public LocaleCatalogConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

package es.ubu.batchdownloader.downloadworker.application;

/**
 * Propaga fallos de disco, almacenamiento, archivo o coordinación que impiden completar el
 * procesamiento, conservando la causa para la política del consumidor.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
public class InfrastructureException extends RuntimeException {
    /**
     * Conserva diagnóstico y causa del fallo de infraestructura sin clasificarlo como rechazo de un
     * instalador.
     *
     * @param message Código o mensaje del fallo de infraestructura, sin incorporar credenciales.
     * @param cause Fallo original conservado para diagnóstico y política de reintentos.
     */
    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}

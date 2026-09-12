package es.ubu.batchdownloader.downloadworker.domain;

/**
 * Centraliza nombres de contratos y claves de enrutamiento de los eventos de descarga para que
 * productores y consumidores utilicen la misma versión.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.domain.DownloadEvents
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadEventEmitter
 * @since 0.1.0
 * @version 0.1.0
 * @category Contratos de descarga
 */
public final class EventTypes {
    /**
     * Versión del esquema de eventos que el worker acepta y produce.
     */
    public static final int CURRENT_VERSION = 1;
    /**
     * Solicitud de procesamiento de un trabajo admitido por Core.
     */
    public static final String JOB_REQUESTED = "download.job.requested";
    /**
     * Solicitud de parada cooperativa de un trabajo.
     */
    public static final String JOB_CANCEL_REQUESTED = "download.job.cancel-requested";
    /**
     * Transición de estado, bytes o integridad de un elemento.
     */
    public static final String JOB_PROGRESSED = "download.job.progressed";
    /**
     * Disponibilidad del ZIP confirmado con su integridad y vigencia.
     */
    public static final String JOB_READY = "download.job.ready";
    /**
     * Aplazamiento no terminal del trabajo por capacidad.
     */
    public static final String JOB_DEFERRED = "download.job.deferred";
    /**
     * Resultado terminal sin contenido entregable del trabajo.
     */
    public static final String JOB_FAILED = "download.job.failed";

    /**
     * Clave AMQP para publicar: transición de estado, bytes o integridad de un elemento.
     */
    public static final String JOB_PROGRESSED_ROUTING_KEY = "download.job.progressed";
    /**
     * Clave AMQP para publicar: disponibilidad del ZIP confirmado con su integridad y vigencia.
     */
    public static final String JOB_READY_ROUTING_KEY = "download.job.ready";
    /**
     * Clave AMQP para publicar: aplazamiento no terminal del trabajo por capacidad.
     */
    public static final String JOB_DEFERRED_ROUTING_KEY = "download.job.deferred";
    /**
     * Clave AMQP para publicar: resultado terminal sin contenido entregable del trabajo.
     */
    public static final String JOB_FAILED_ROUTING_KEY = "download.job.failed";

    /**
     * Impide instanciar el catálogo estático de tipos y claves de eventos.
     */
    private EventTypes() {
    }
}

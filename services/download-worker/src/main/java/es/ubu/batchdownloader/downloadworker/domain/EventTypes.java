package es.ubu.batchdownloader.downloadworker.domain;

/**
 * Implementa el componente {@code EventTypes}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class EventTypes {
    /**
     * Constante que define {@code CURRENT_VERSION}.
     */
    public static final int CURRENT_VERSION = 1;
    /**
     * Constante que define {@code JOB_REQUESTED}.
     */
    public static final String JOB_REQUESTED = "download.job.requested";
    /**
     * Constante que define {@code JOB_CANCEL_REQUESTED}.
     */
    public static final String JOB_CANCEL_REQUESTED = "download.job.cancel-requested";
    /**
     * Constante que define {@code JOB_PROGRESSED}.
     */
    public static final String JOB_PROGRESSED = "download.job.progressed";
    /**
     * Constante que define {@code JOB_READY}.
     */
    public static final String JOB_READY = "download.job.ready";
    /** Constante que define una espera no terminal por capacidad. */
    public static final String JOB_DEFERRED = "download.job.deferred";
    /**
     * Constante que define {@code JOB_FAILED}.
     */
    public static final String JOB_FAILED = "download.job.failed";

    /**
     * Constante que define {@code JOB_PROGRESSED_ROUTING_KEY}.
     */
    public static final String JOB_PROGRESSED_ROUTING_KEY = "download.job.progressed";
    /**
     * Constante que define {@code JOB_READY_ROUTING_KEY}.
     */
    public static final String JOB_READY_ROUTING_KEY = "download.job.ready";
    /** Clave de enrutado para una espera no terminal por capacidad. */
    public static final String JOB_DEFERRED_ROUTING_KEY = "download.job.deferred";
    /**
     * Constante que define {@code JOB_FAILED_ROUTING_KEY}.
     */
    public static final String JOB_FAILED_ROUTING_KEY = "download.job.failed";

    /**
     * Inicializa una instancia de {@code EventTypes}.
     */
    private EventTypes() {
    }
}

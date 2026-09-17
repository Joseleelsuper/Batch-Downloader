package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;

/**
 * Difunde vistas de trabajos a observadores después de confirmar la transición que representan.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobNotifications
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobView
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public interface DownloadJobNotifier {
    /**
     * Entrega a los suscriptores la instantánea confirmada del trabajo, incluidos sus elementos.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    void changed(DownloadJobView job);
}

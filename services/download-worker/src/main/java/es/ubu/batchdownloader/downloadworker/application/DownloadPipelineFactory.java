package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.nio.file.Path;
import java.util.List;

/**
 * Abre ventanas de descarga con dependencias compartidas y estado propio de cada trabajo, separando
 * su composición de la coordinación del procesador.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Procesamiento de descargas
 */
@FunctionalInterface
public interface DownloadPipelineFactory {
    /**
     * Inicia la ventana de transferencias y devuelve el cursor de sus resultados.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param items Fuentes resueltas que conservan los identificadores de los elementos admitidos.
     * @param directory Directorio temporal exclusivo en el que se escribirán los instaladores.
     * @param window Máximo de tareas simultáneas, acotado por el número de elementos.
     * @return pipeline con presupuesto de bytes y nombres únicos independientes por trabajo.
     */
    DownloadPipeline create(DownloadJobRequestedEvent event, List<ResolvedDownloadItem> items,
            Path directory, int window, DownloadBudget budget);
}

package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.bundle.BundleDtos.OwnBundleSummary;
import es.ubu.batchdownloader.identity.application.IdentityView;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agrupa las proyecciones del historial y el panel personal que consume la interfaz de cuenta.
 *
 * @see es.ubu.batchdownloader.identity.api.AccountController
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.AccountOverviewRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
public final class AccountDtos {
    /**
     * Impide instanciar el contenedor de contratos del panel personal.
     */
    private AccountDtos() {}

    /**
     * Relaciona una descarga completada con su trabajo y los metadatos actuales de la aplicación,
     * conservando el nombre histórico.
     *
     * @param appId UUID de la aplicación registrada en el historial.
     * @param appName Nombre de la aplicación conservado en el momento de la descarga.
     * @param slug Ruta amigable vigente del catálogo, o null si la aplicación ya no está
     *     disponible.
     * @param iconUrl Icono vigente del catálogo, o null si no existe una aplicación asociada.
     * @param jobId UUID del trabajo que completó el elemento del historial.
     * @param downloadedAt Fecha de finalización registrada por Core para el elemento.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    public record DownloadHistoryItem(
            String appId,
            String appName,
            String slug,
            String iconUrl,
            String jobId,
            LocalDateTime downloadedAt) {}

    /**
     * Entrega una página de descargas personales y el total necesario para navegar por el
     * historial.
     *
     * @param data Elementos de historial de la página, ordenados del más reciente al más antiguo.
     * @param page Página solicitada, numerada desde uno.
     * @param pageSize Número de filas solicitado por página; el controlador lo limita a 1–60.
     * @param total Total de elementos de historial de la cuenta, antes de paginar.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    public record DownloadHistoryPage(
            List<DownloadHistoryItem> data, int page, int pageSize, long total) {}

    /**
     * Resume los bundles personales por visibilidad y el tamaño del historial de descargas.
     *
     * @param bundles Total de bundles personales de cualquier visibilidad.
     * @param publicBundles Número de bundles personales con visibilidad pública.
     * @param privateBundles Número de bundles personales con visibilidad privada.
     * @param downloads Número de elementos completados conservados en el historial.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    public record DashboardCounts(
            long bundles,
            long publicBundles,
            long privateBundles,
            long downloads) {}

    /**
     * Reúne la identidad de sesión, contadores y actividad reciente del panel personal.
     *
     * @param account Vista actual de la cuenta habilitada.
     * @param counts Totales de bundles propios por visibilidad y elementos descargados.
     * @param recentDownloads Diez últimas entradas del historial personal.
     * @param recentBundles Primera página de hasta seis bundles personales.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    public record AccountDashboard(
            IdentityView account,
            DashboardCounts counts,
            List<DownloadHistoryItem> recentDownloads,
            List<OwnBundleSummary> recentBundles) {}
}

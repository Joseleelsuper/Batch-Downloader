package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.common.UuidBytes;
import es.ubu.batchdownloader.identity.api.AccountDtos.DashboardCounts;
import es.ubu.batchdownloader.identity.api.AccountDtos.DownloadHistoryItem;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consulta actividad y contadores personales por UUID, conservando el historial aunque desaparezca
 * la aplicación del catálogo.
 *
 * @see es.ubu.batchdownloader.identity.api.AccountController
 * @see es.ubu.batchdownloader.identity.api.AccountDtos
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Repository
public class AccountOverviewRepository {
    private final JdbcTemplate jdbc;

    /**
     * Conecta las consultas SQL de historial y bundles personales.
     *
     * @param jdbc Acceso SQL al historial y los bundles de la cuenta consultada.
     */
    public AccountOverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Pagina el historial propio por fecha e identidad descendentes y enriquece cada entrada con
     * slug e icono actuales cuando existen.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param page Página numerada desde uno y previamente validada por el controlador.
     * @param pageSize Cantidad positiva de entradas; el controlador aplica el límite de tamaño.
     * @return entradas recientes con nombre histórico conservado.
     */
    public List<DownloadHistoryItem> downloads(UUID userId, int page, int pageSize) {
        return jdbc.query(
                """
                SELECT history.app_id, history.app_name, history.job_id, history.downloaded_at,
                       app.slug, app.icon_url
                FROM user_download_history history
                LEFT JOIN software_apps app ON app.id = history.app_id
                WHERE history.user_id = ?
                ORDER BY history.downloaded_at DESC, history.id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new DownloadHistoryItem(
                        UuidBytes.toUuid(rs.getBytes("app_id")).toString(),
                        rs.getString("app_name"),
                        rs.getString("slug"),
                        rs.getString("icon_url"),
                        rs.getString("job_id"),
                        rs.getTimestamp("downloaded_at").toLocalDateTime()),
                userId.toString(), pageSize, (page - 1) * pageSize);
    }

    /**
     * Cuenta todos los elementos del historial del propietario.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @return total del historial, o cero si la consulta no aporta valor.
     */
    public long downloadCount(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_download_history WHERE user_id = ?",
                Long.class, userId.toString());
        return count == null ? 0 : count;
    }

    /**
     * Agrupa los bundles personales por visibilidad y añade el total de elementos descargados.
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @return contadores del panel de cuenta con ceros cuando no hay actividad.
     */
    public DashboardCounts counts(UUID userId) {
        BundleCounts bundles = jdbc.queryForObject(
                """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(visibility = 'public'), 0) AS public_total,
                       COALESCE(SUM(visibility = 'private'), 0) AS private_total
                FROM bundles
                WHERE owner_id = ? AND type = 'user'
                """,
                (rs, rowNum) -> new BundleCounts(
                        rs.getLong("total"), rs.getLong("public_total"),
                        rs.getLong("private_total")),
                userId.toString());
        return new DashboardCounts(
                bundles == null ? 0 : bundles.total(),
                bundles == null ? 0 : bundles.publicTotal(),
                bundles == null ? 0 : bundles.privateTotal(),
                downloadCount(userId));
    }

    /**
     * Recoge en una sola consulta los totales de bundles personales por visibilidad.
     *
     * @param total Número total de bundles personales de la cuenta.
     * @param publicTotal Número de bundles personales con visibilidad pública.
     * @param privateTotal Número de bundles personales con visibilidad privada.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    private record BundleCounts(long total, long publicTotal, long privateTotal) {}
}

package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Exporta el catálogo activo a CSV eligiendo el primer instalador descargable de cada plataforma
 * según el orden de prioridad de fuentes.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see AdminAppRepository.AppCsvExport
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración del catálogo
 */
@Repository
public class AdminAppExportRepository {
    private final JdbcTemplate jdbc;

    /**
     * Conecta la consulta de aplicaciones y fuentes que alimenta la exportación.
     *
     * @param jdbc Acceso SQL que participa en la transacción administrativa del llamador.
     */
    public AdminAppExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Agrupa candidatos por aplicación en orden de nombre y elige por plataforma la primera
     * referencia según versión, prioridad, puntuación y fecha de comprobación.
     *
     * @return CSV con cabecera, referencias exactas y None donde falta un valor.
     */
    public AppCsvExport exportCsv() {
        List<ExportCandidate> candidates = jdbc.query(
                """
                SELECT HEX(a.id) AS app_key, a.name, a.winstall_id, a.official_url,
                       ds.operating_system, rs.extension, BIN_TO_UUID(rs.id) AS source_ref
                FROM software_apps a
                LEFT JOIN download_sources ds ON ds.software_app_id = a.id
                    AND ds.catalog_available = 1
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.catalog_downloadable = 1
                WHERE a.app_status = 'active'
                ORDER BY a.normalized_name ASC,
                         a.id ASC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC,
                         rs.checked_at DESC
                """,
                this::mapCandidate);
        Map<String, ExportRow> rows = new LinkedHashMap<>();
        for (ExportCandidate candidate : candidates) {
            ExportRow row = rows.computeIfAbsent(candidate.appKey(), key -> new ExportRow(
                    candidate.name(),
                    winstallUrl(candidate.winstallId()),
                    blankToNone(candidate.officialUrl())));
            String platform = platformKey(candidate.operatingSystem(), candidate.extension());
            if (platform != null && candidate.sourceRef() != null && !candidate.sourceRef().isBlank()) {
                row.putIfMissing(platform, candidate.sourceRef());
            }
        }

        StringBuilder csv = new StringBuilder(
                "Nombre,Winstall,URL,WindowsSourceRef,LinuxSourceRef,MacOSSourceRef\r\n");
        for (ExportRow row : rows.values()) {
            csv.append(csvCell(row.name()))
                    .append(',')
                    .append(csvCell(row.winstall()))
                    .append(',')
                    .append(csvCell(row.officialUrl()))
                    .append(',')
                    .append(csvCell(row.windows()))
                    .append(',')
                    .append(csvCell(row.linux()))
                    .append(',')
                    .append(csvCell(row.macos()))
                    .append("\r\n");
        }
        return new AppCsvExport(csv.toString(), rows.size());
    }

    /**
     * Extrae de una fila los metadatos de aplicación y su posible instalador sin perder los null de
     * las uniones externas.
     *
     * @param rs Fila SQL actual de una aplicación y su posible instalador.
     * @param rowNum Índice de fila proporcionado por JDBC; no afecta al mapeo.
     * @return candidato a una fila de exportación.
     * @throws java.sql.SQLException si no se pueden leer las columnas de la fila.
     */
    private ExportCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new ExportCandidate(
                rs.getString("app_key"),
                rs.getString("name"),
                rs.getString("winstall_id"),
                rs.getString("official_url"),
                rs.getString("operating_system"),
                rs.getString("extension"),
                rs.getString("source_ref"));
    }

    /**
     * Construye el enlace público del paquete cuando su identificador procede de Winstall.
     *
     * @param winstallId Identificador del paquete Winstall; manual. identifica altas
     *     administrativas.
     * @return enlace a Winstall; None para identificadores ausentes o manuales.
     */
    private String winstallUrl(String winstallId) {
        if (isBlank(winstallId) || winstallId.startsWith("manual.")) {
            return "None";
        }
        return "https://winstall.app/apps/" + winstallId.trim();
    }

    /**
     * Representa los campos ausentes con el literal acordado para el CSV.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return None si no hay texto; en otro caso el texto recortado.
     */
    private String blankToNone(String value) {
        return isBlank(value) ? "None" : value.trim();
    }

    /**
     * Clasifica primero por sistema operativo y, si no se reconoce, por un formato de instalador
     * conocido.
     *
     * @param operatingSystem Plataforma declarada en la fuente; se normaliza antes de clasificarla.
     * @param extension Formato del instalador utilizado si la plataforma no se reconoce.
     * @return windows, linux o macos; null si no hay una clasificación compatible.
     */
    private String platformKey(String operatingSystem, String extension) {
        String os = operatingSystem == null ? "" : operatingSystem.toLowerCase(Locale.ROOT).trim();
        if (os.contains("windows") || os.equals("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            return "macos";
        }

        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replace(".", "").trim();
        return switch (ext) {
            case "exe", "msi", "msix", "appx" -> "windows";
            case "deb", "rpm", "appimage", "flatpak" -> "linux";
            case "dmg", "pkg" -> "macos";
            default -> null;
        };
    }

    /**
     * Sustituye valores blancos por None y protege con comillas los campos que contienen
     * separadores, comillas o saltos de línea.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return celda CSV con comillas internas duplicadas cuando es necesario.
     */
    private String csvCell(String value) {
        String safe = isBlank(value) ? "None" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    /**
     * Detecta valores ausentes antes de normalizar las celdas exportadas.
     *
     * @param value Texto que se normaliza o comprueba; se admite null donde se indica.
     * @return true para null o texto sin caracteres visibles.
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Conserva una combinación de aplicación y fuente para agruparla sin cambiar el orden de
     * prioridad SQL.
     *
     * @param appKey UUID hexadecimal usado para agrupar todas las fuentes de una aplicación.
     * @param name Nombre visible de la aplicación.
     * @param winstallId Identificador del paquete Winstall; manual. identifica altas
     *     administrativas.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param operatingSystem Plataforma declarada en la fuente; se normaliza antes de clasificarla.
     * @param extension Formato del instalador utilizado si la plataforma no se reconoce.
     * @param sourceRef UUID textual exacto de un instalador descargable.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración del catálogo
     */
    private record ExportCandidate(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String operatingSystem,
            String extension,
            String sourceRef) {}

    /**
     * Acumula como máximo una referencia exacta por plataforma, manteniendo None para las que no
     * disponen de instalador.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración del catálogo
     */
    private static final class ExportRow {
        /**
         * Nombre visible.
         */
        private final String name;
        /**
         * Enlace a Winstall o None.
         */
        private final String winstall;
        /**
         * Página oficial o None.
         */
        private final String officialUrl;
        /**
         * Referencia Windows o None.
         */
        private String windows = "None";
        /**
         * Referencia Linux o None.
         */
        private String linux = "None";
        /**
         * Referencia macOS o None.
         */
        private String macos = "None";

        /**
         * Inicializa los datos comunes de una aplicación antes de seleccionar sus instaladores por
         * plataforma.
         *
         * @param name Nombre visible de la aplicación.
         * @param winstall Enlace a Winstall o el literal None si la aplicación es manual.
         * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
         */
        private ExportRow(String name, String winstall, String officialUrl) {
            this.name = name;
            this.winstall = winstall;
            this.officialUrl = officialUrl;
        }

        /**
         * Conserva la primera referencia de cada plataforma y descarta las siguientes, cuyo orden
         * SQL expresa menor prioridad.
         *
         * @param platform Columna de destino: windows, linux o macos.
         * @param sourceRef UUID textual exacto de un instalador descargable.
         */
        private void putIfMissing(String platform, String sourceRef) {
            if ("windows".equals(platform) && "None".equals(windows)) {
                windows = sourceRef;
            } else if ("linux".equals(platform) && "None".equals(linux)) {
                linux = sourceRef;
            } else if ("macos".equals(platform) && "None".equals(macos)) {
                macos = sourceRef;
            }
        }

        /**
         * Devuelve nombre visible para escribir la celda correspondiente del CSV.
         *
         * @return nombre visible.
         */
        private String name() {
            return name;
        }

        /**
         * Devuelve enlace a Winstall o None para escribir la celda correspondiente del CSV.
         *
         * @return enlace a Winstall o None.
         */
        private String winstall() {
            return winstall;
        }

        /**
         * Devuelve página oficial o None para escribir la celda correspondiente del CSV.
         *
         * @return página oficial o None.
         */
        private String officialUrl() {
            return officialUrl;
        }

        /**
         * Devuelve referencia Windows o None para escribir la celda correspondiente del CSV.
         *
         * @return referencia Windows o None.
         */
        private String windows() {
            return windows;
        }

        /**
         * Devuelve referencia Linux o None para escribir la celda correspondiente del CSV.
         *
         * @return referencia Linux o None.
         */
        private String linux() {
            return linux;
        }

        /**
         * Devuelve referencia macOS o None para escribir la celda correspondiente del CSV.
         *
         * @return referencia macOS o None.
         */
        private String macos() {
            return macos;
        }
    }
}

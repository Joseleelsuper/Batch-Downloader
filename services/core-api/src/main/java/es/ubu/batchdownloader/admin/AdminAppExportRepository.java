package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Exporta el catálogo activo a CSV con el primer instalador descargable de cada plataforma,
 * etiquetas y editor, según el orden de prioridad de fuentes.
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
     * Lee cada aplicación una sola vez y elige por plataforma la primera referencia según versión,
     * prioridad, puntuación y fecha de comprobación.
     *
     * @return CSV con cabecera, referencias exactas y None donde falta un valor.
     */
    public AppCsvExport exportCsv() {
        List<ExportCandidate> candidates = jdbc.query(
                """
                SELECT HEX(a.id) AS app_key, a.name, a.winstall_id, a.official_url, a.publisher,
                       (
                           SELECT BIN_TO_UUID(rs.id)
                           FROM download_sources ds
                           JOIN resolved_sources rs ON rs.download_source_id = ds.id
                               AND rs.catalog_downloadable = 1
                           WHERE ds.software_app_id = a.id
                               AND ds.catalog_available = 1
                               AND (
                                   LOWER(TRIM(COALESCE(ds.operating_system, ''))) LIKE '%windows%'
                                   OR LOWER(TRIM(COALESCE(ds.operating_system, ''))) = 'win'
                                   OR (
                                       LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%windows%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) <> 'win'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%linux%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%mac%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%darwin%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%osx%'
                                       AND TRIM(REPLACE(LOWER(COALESCE(rs.extension, '')), '.', ''))
                                           IN ('exe', 'msi', 'msix', 'appx')
                                   )
                               )
                           ORDER BY rs.is_latest DESC,
                                    COALESCE(rs.release_rank, 9999) ASC,
                                    (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                                    rs.score DESC,
                                    rs.checked_at DESC
                           LIMIT 1
                       ) AS windows_source_ref,
                       (
                           SELECT BIN_TO_UUID(rs.id)
                           FROM download_sources ds
                           JOIN resolved_sources rs ON rs.download_source_id = ds.id
                               AND rs.catalog_downloadable = 1
                           WHERE ds.software_app_id = a.id
                               AND ds.catalog_available = 1
                               AND (
                                   LOWER(TRIM(COALESCE(ds.operating_system, ''))) LIKE '%linux%'
                                   OR (
                                       LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%windows%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) <> 'win'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%linux%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%mac%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%darwin%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%osx%'
                                       AND TRIM(REPLACE(LOWER(COALESCE(rs.extension, '')), '.', ''))
                                           IN ('deb', 'rpm', 'appimage', 'flatpak')
                                   )
                               )
                           ORDER BY rs.is_latest DESC,
                                    COALESCE(rs.release_rank, 9999) ASC,
                                    (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                                    rs.score DESC,
                                    rs.checked_at DESC
                           LIMIT 1
                       ) AS linux_source_ref,
                       (
                           SELECT BIN_TO_UUID(rs.id)
                           FROM download_sources ds
                           JOIN resolved_sources rs ON rs.download_source_id = ds.id
                               AND rs.catalog_downloadable = 1
                           WHERE ds.software_app_id = a.id
                               AND ds.catalog_available = 1
                               AND (
                                   LOWER(TRIM(COALESCE(ds.operating_system, ''))) LIKE '%mac%'
                                   OR LOWER(TRIM(COALESCE(ds.operating_system, ''))) LIKE '%darwin%'
                                   OR LOWER(TRIM(COALESCE(ds.operating_system, ''))) LIKE '%osx%'
                                   OR (
                                       LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%windows%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) <> 'win'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%linux%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%mac%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%darwin%'
                                       AND LOWER(TRIM(COALESCE(ds.operating_system, ''))) NOT LIKE '%osx%'
                                       AND TRIM(REPLACE(LOWER(COALESCE(rs.extension, '')), '.', ''))
                                           IN ('dmg', 'pkg')
                                   )
                               )
                           ORDER BY rs.is_latest DESC,
                                    COALESCE(rs.release_rank, 9999) ASC,
                                    (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                                    rs.score DESC,
                                    rs.checked_at DESC
                           LIMIT 1
                       ) AS macos_source_ref
                FROM software_apps a
                WHERE a.app_status = 'active'
                ORDER BY a.normalized_name ASC, a.id ASC
                """,
                this::mapCandidate);
        Map<String, ExportRow> rows = new LinkedHashMap<>();
        for (ExportCandidate candidate : candidates) {
            rows.put(candidate.appKey(), new ExportRow(
                    candidate.name(),
                    winstallUrl(candidate.winstallId()),
                    blankToNone(candidate.officialUrl()),
                    blankToNone(candidate.windowsSourceRef()),
                    blankToNone(candidate.linuxSourceRef()),
                    blankToNone(candidate.macosSourceRef()),
                    blankToNone(candidate.publisher())));
        }

        jdbc.query(
                """
                SELECT HEX(tags.software_app_id) AS app_key, tags.tag
                FROM software_app_tags tags
                JOIN software_apps apps ON apps.id = tags.software_app_id
                WHERE apps.app_status = 'active'
                ORDER BY tags.software_app_id, tags.tag
                """,
                rs -> {
                    ExportRow row = rows.get(rs.getString("app_key"));
                    if (row != null) {
                        row.addTag(rs.getString("tag"));
                    }
                });

        StringBuilder csv = new StringBuilder(
                "Nombre,Winstall,URL,WindowsSourceRef,LinuxSourceRef,MacOSSourceRef,tags,editor\r\n");
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
                    .append(',')
                    .append(csvCell(String.join("; ", row.tags())))
                    .append(',')
                    .append(csvCell(row.editor()))
                    .append("\r\n");
        }
        return new AppCsvExport(csv.toString(), rows.size());
    }

    /**
     * Extrae metadatos de aplicación y la mejor referencia descargable de cada plataforma.
     *
     * @param rs Fila SQL actual de una aplicación.
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
                rs.getString("windows_source_ref"),
                rs.getString("linux_source_ref"),
                rs.getString("macos_source_ref"),
                rs.getString("publisher"));
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
     * Representa una aplicación y sus referencias prioritarias ya limitadas a una por plataforma.
     *
     * @param appKey UUID hexadecimal usado para identificar la aplicación.
     * @param name Nombre visible de la aplicación.
     * @param winstallId Identificador del paquete Winstall; manual. identifica altas
     *     administrativas.
     * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
     * @param windowsSourceRef UUID textual exacto del instalador preferido para Windows.
     * @param linuxSourceRef UUID textual exacto del instalador preferido para Linux.
     * @param macosSourceRef UUID textual exacto del instalador preferido para macOS.
     * @param publisher Editor singular de la aplicación.
     * @since 0.1.0
     * @version 0.1.0
     * @category Administración del catálogo
     */
    private record ExportCandidate(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String windowsSourceRef,
            String linuxSourceRef,
            String macosSourceRef,
            String publisher) {}

    /**
     * Conserva metadatos CSV ya normalizados y las etiquetas asociadas a una aplicación.
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
        /** Referencia Windows o None. */
        private final String windows;
        /** Referencia Linux o None. */
        private final String linux;
        /** Referencia macOS o None. */
        private final String macos;
        /** Editor o None. */
        private final String editor;
        /** Etiquetas ordenadas de la aplicación. */
        private final List<String> tags = new ArrayList<>();

        /**
         * Inicializa metadatos de aplicación y referencias prioritarias ya seleccionadas.
         *
         * @param name Nombre visible de la aplicación.
         * @param winstall Enlace a Winstall o el literal None si la aplicación es manual.
         * @param officialUrl Página oficial pública de la aplicación; no es un instalador resuelto.
         * @param windows Referencia preferida para Windows o None.
         * @param linux Referencia preferida para Linux o None.
         * @param macos Referencia preferida para macOS o None.
         * @param editor Editor o None.
         */
        private ExportRow(
                String name,
                String winstall,
                String officialUrl,
                String windows,
                String linux,
                String macos,
                String editor) {
            this.name = name;
            this.winstall = winstall;
            this.officialUrl = officialUrl;
            this.windows = windows;
            this.linux = linux;
            this.macos = macos;
            this.editor = editor;
        }

        /**
         * Añade una etiqueta asociada a la aplicación.
         *
         * @param tag Etiqueta visible del catálogo.
         */
        private void addTag(String tag) {
            tags.add(tag);
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

        /**
         * Devuelve las etiquetas de la aplicación.
         *
         * @return etiquetas ordenadas.
         */
        private List<String> tags() {
            return tags;
        }

        /**
         * Devuelve editor para escribir la celda correspondiente.
         *
         * @return editor o None.
         */
        private String editor() {
            return editor;
        }
    }
}

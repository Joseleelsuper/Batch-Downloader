package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Produce nombres compatibles con extracción de archivos y evita colisiones con otros instaladores
 * y con los recursos del runtime incluido en el ZIP.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @see es.ubu.batchdownloader.downloadworker.application.ManualShortcutWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Resultados y empaquetado
 */
@Component
public class FilenamePolicy {
    /**
     * Valor compartido que fija m a x  f i l e n a m e  l e n g t h para el comportamiento del
     * componente.
     */
    private static final int MAX_FILENAME_LENGTH = 180;
    /**
     * Valor compartido que fija w i n d o w s  r e s e r v e d  n a m e s para el comportamiento
     * del componente.
     */
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * Prioriza el nombre propuesto, usa como respaldo el último segmento de la URI y sanea y
     * reserva un nombre único.
     *
     * @param item Instalador resuelto que aporta nombre, URI y UUID para los respaldos.
     * @param usedNames Conjunto mutable de nombres ya reservados en minúsculas, compartido por las
     *     entradas de su grupo.
     * @return nombre seguro sin colisión en el conjunto recibido.
     */
    public String filenameFor(ResolvedDownloadItem item, Set<String> usedNames) {
        String requested = item.filename();
        String fallback = filenameFromUrl(item);
        String sanitized = sanitize(requested == null || requested.isBlank() ? fallback : requested);
        return unique(sanitized, usedNames);
    }

    /**
     * Reserva desde el inicio los nombres del manifiesto, scripts, configuración y directorios del
     * runtime.
     *
     * @return nuevo conjunto mutable de nombres reservados en minúsculas.
     */
    public Set<String> newNameSet() {
        return new HashSet<>(Set.of("manifest.json", "install.sh", "uninstall.sh", "update.sh",
                "rollback.sh", "installer.conf", "readme.md", "version", "checksums.sha256",
                "config", "lib", "bin", "plugins", "signatures"));
    }

    /**
     * Construye un nombre .url a partir del programa y lo sanea y deduplica dentro del grupo de
     * accesos manuales.
     *
     * @param appName Nombre visible del programa para generar el acceso manual; si falta se utiliza
     *     Aplicacion.
     * @param usedNames Conjunto mutable de nombres ya reservados en minúsculas, compartido por las
     *     entradas de su grupo.
     * @return nombre único del acceso manual.
     */
    public String manualShortcutFilename(String appName, Set<String> usedNames) {
        String normalizedName = appName == null || appName.isBlank() ? "Aplicacion" : appName;
        return unique(sanitize(normalizedName + ".url"), usedNames);
    }

    /**
     * Retira separadores, controles y caracteres incompatibles, evita nombres reservados de Windows
     * y limita la longitud conservando la extensión.
     *
     * @param value Texto propuesto que se normaliza o valida antes de incluirlo en el archivo.
     * @return nombre saneado; installer.bin si no queda un nombre significativo.
     */
    String sanitize(String value) {
        String sanitized = value == null ? "installer.bin" : value
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-")
                .replaceAll("\\s+", " ")
                .strip()
                .replaceAll("^[. -]+|[. ]+$", "");
        if (sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")) {
            sanitized = "installer.bin";
        }
        ExtensionParts initialParts = extensionParts(sanitized);
        if (WINDOWS_RESERVED_NAMES.contains(initialParts.base().toUpperCase(Locale.ROOT))) {
            sanitized = "_" + sanitized;
        }
        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            ExtensionParts parts = extensionParts(sanitized);
            int baseLength = Math.max(1, MAX_FILENAME_LENGTH - parts.extension().length());
            sanitized = parts.base().substring(0, Math.min(parts.base().length(), baseLength))
                    + parts.extension();
        }
        return sanitized;
    }

    /**
     * Usa el último segmento no vacío de la ruta de descarga como nombre de respaldo.
     *
     * @param item Instalador resuelto con URI y UUID de elemento.
     * @return segmento de ruta o installer-UUID.bin cuando no hay nombre.
     */
    private String filenameFromUrl(ResolvedDownloadItem item) {
        String path = item.url().getPath();
        if (path != null && !path.isBlank()) {
            int separator = path.lastIndexOf('/');
            String name = path.substring(separator + 1);
            if (!name.isBlank()) {
                return name;
            }
        }
        return "installer-" + item.itemId() + ".bin";
    }

    /**
     * Reserva el nombre sin distinguir mayúsculas y añade sufijos desde -2 antes de la extensión si
     * ya estaba utilizado.
     *
     * @param filename Nombre de archivo que se separa o deduplica conservando su extensión.
     * @param usedNames Conjunto mutable de nombres ya reservados en minúsculas, compartido por las
     *     entradas de su grupo.
     * @return nombre recién incorporado al conjunto de reservas.
     */
    private String unique(String filename, Set<String> usedNames) {
        String candidate = filename;
        ExtensionParts parts = extensionParts(filename);
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = parts.base() + "-" + suffix++ + parts.extension();
        }
        return candidate;
    }

    /**
     * Separa el nombre conservando completas .tar.gz y .pkg.tar.zst; para otros formatos usa el
     * último punto que no sea inicial.
     *
     * @param filename Nombre de archivo que se separa o deduplica conservando su extensión.
     * @return base y extensión, que puede ser vacía.
     */
    private ExtensionParts extensionParts(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pkg.tar.zst")) {
            return new ExtensionParts(filename.substring(0, filename.length() - 12),
                    filename.substring(filename.length() - 12));
        }
        if (lower.endsWith(".tar.gz")) {
            return new ExtensionParts(filename.substring(0, filename.length() - 7), filename.substring(filename.length() - 7));
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return new ExtensionParts(filename, "");
        }
        return new ExtensionParts(filename.substring(0, dot), filename.substring(dot));
    }

    /**
     * Permite añadir sufijos o limitar longitud sin romper extensiones compuestas reconocidas.
     *
     * @param base Nombre del archivo sin la extensión reconocida.
     * @param extension Extensión con punto inicial; vacía cuando el nombre no tiene una extensión
     *     reconocida.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Resultados y empaquetado
     */
    private record ExtensionParts(String base, String extension) {
    }
}

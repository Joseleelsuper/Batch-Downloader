package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Implementa el componente {@code FilenamePolicy}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class FilenamePolicy {
    /**
     * Constante que define {@code MAX_FILENAME_LENGTH}.
     */
    private static final int MAX_FILENAME_LENGTH = 180;
    /**
     * Constante que define {@code WINDOWS_RESERVED_NAMES}.
     */
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /**
     * Ejecuta la operación {@code filenameFor}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @param usedNames Valor de {@code usedNames} utilizado por la operación.
     * @return Resultado producido por {@code filenameFor}.
     */
    public String filenameFor(ResolvedDownloadItem item, Set<String> usedNames) {
        String requested = item.filename();
        String fallback = filenameFromUrl(item);
        String sanitized = sanitize(requested == null || requested.isBlank() ? fallback : requested);
        return unique(sanitized, usedNames);
    }

    /**
     * Ejecuta la operación {@code newNameSet}.
     *
     * @return Colección de elementos obtenidos por la operación.
     */
    public Set<String> newNameSet() {
        return new HashSet<>();
    }

    /**
     * Ejecuta la operación {@code manualShortcutFilename}.
     *
     * @param appName Valor de {@code appName} utilizado por la operación.
     * @param usedNames Valor de {@code usedNames} utilizado por la operación.
     * @return Resultado producido por {@code manualShortcutFilename}.
     */
    public String manualShortcutFilename(String appName, Set<String> usedNames) {
        String normalizedName = appName == null || appName.isBlank() ? "Aplicacion" : appName;
        return unique(sanitize(normalizedName + ".url"), usedNames);
    }

    /**
     * Ejecuta la operación {@code sanitize}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code sanitize}.
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
     * Ejecuta la operación {@code filenameFromUrl}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @return Resultado producido por {@code filenameFromUrl}.
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
     * Ejecuta la operación {@code unique}.
     *
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @param usedNames Valor de {@code usedNames} utilizado por la operación.
     * @return Resultado producido por {@code unique}.
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
     * Ejecuta la operación {@code extensionParts}.
     *
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @return Resultado producido por {@code extensionParts}.
     */
    private ExtensionParts extensionParts(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
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
     * Representa los datos inmutables de {@code ExtensionParts}.
     *
     * @param base Valor de {@code base} incluido en el record.
     * @param extension Valor de {@code extension} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record ExtensionParts(String base, String extension) {
    }
}

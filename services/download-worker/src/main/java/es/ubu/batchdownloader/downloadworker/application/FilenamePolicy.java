package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FilenamePolicy {
    private static final int MAX_FILENAME_LENGTH = 180;
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    public String filenameFor(ResolvedDownloadItem item, Set<String> usedNames) {
        String requested = item.filename();
        String fallback = filenameFromUrl(item);
        String sanitized = sanitize(requested == null || requested.isBlank() ? fallback : requested);
        return unique(sanitized, usedNames);
    }

    public Set<String> newNameSet() {
        return new HashSet<>();
    }

    public String manualShortcutFilename(String appName, Set<String> usedNames) {
        String normalizedName = appName == null || appName.isBlank() ? "Aplicacion" : appName;
        return unique(sanitize(normalizedName + ".url"), usedNames);
    }

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

    private String unique(String filename, Set<String> usedNames) {
        String candidate = filename;
        ExtensionParts parts = extensionParts(filename);
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = parts.base() + "-" + suffix++ + parts.extension();
        }
        return candidate;
    }

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

    private record ExtensionParts(String base, String extension) {
    }
}

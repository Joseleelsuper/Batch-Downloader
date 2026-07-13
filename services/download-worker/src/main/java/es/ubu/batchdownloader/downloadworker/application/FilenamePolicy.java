package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FilenamePolicy {
    private static final int MAX_FILENAME_LENGTH = 180;

    public String filenameFor(ResolvedDownloadItem item, Set<String> usedNames) {
        String requested = item.filename();
        String fallback = filenameFromUrl(item);
        String sanitized = sanitize(requested == null || requested.isBlank() ? fallback : requested);
        return unique(sanitized, usedNames);
    }

    public Set<String> newNameSet() {
        return new HashSet<>();
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

package es.ubu.batchdownloader.catalog;

import java.util.List;
import java.util.Locale;

/** Proyección pública de capacidades; no revela recetas, claves ni URLs de actualización. */
public final class LinuxInstallationSupport {
    private LinuxInstallationSupport() {}

    public static List<String> targets(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case ".deb" -> List.of("apt");
            case ".rpm" -> List.of("dnf", "zypper");
            case ".pkg.tar.zst" -> List.of("pacman");
            case ".appimage", ".tar.gz", ".jar" -> List.of("apt", "dnf", "pacman", "zypper", "portable");
            default -> List.of();
        };
    }

    public static String support(String os, String extension, String approvedStrategy) {
        if (!"linux".equals(os)) return "not_applicable";
        if (approvedStrategy != null) return "manual".equals(approvedStrategy) ? "manual" : "automatic";
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case ".deb", ".rpm", ".pkg.tar.zst", ".appimage" -> "automatic";
            default -> "manual";
        };
    }
}

package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.List;
import java.util.Set;

/** Preferencia explícita. Los clientes antiguos conservan la selección OR existente. */
public record LinuxTarget(String manager, String architecture) {
    public LinuxTarget {
        if (!Set.of("apt", "dnf", "pacman", "zypper", "portable").contains(manager == null ? "" : manager)
                || !Set.of("x86_64", "x86", "aarch64").contains(architecture == null ? "" : architecture)) {
            throw new BadRequestException("invalid_linux_target", "Indica distribución y arquitectura Linux.");
        }
    }

    public static LinuxTarget optional(String manager, String architecture, List<String> systems) {
        if (manager == null && architecture == null) return null;
        if (!List.of("linux").equals(systems)) {
            throw new BadRequestException("linux_target_requires_linux", "El destino solo se aplica a lotes Linux.");
        }
        return new LinuxTarget(manager, architecture);
    }

    public List<String> extensions() {
        return switch (manager) {
            case "apt" -> List.of(".deb", ".appimage", ".tar.gz", ".jar");
            case "dnf", "zypper" -> List.of(".rpm", ".appimage", ".tar.gz", ".jar");
            case "pacman" -> List.of(".pkg.tar.zst", ".appimage", ".tar.gz", ".jar");
            default -> List.of(".appimage", ".tar.gz", ".jar");
        };
    }
}

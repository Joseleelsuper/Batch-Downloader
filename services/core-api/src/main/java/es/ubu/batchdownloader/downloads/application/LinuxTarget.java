package es.ubu.batchdownloader.downloads.application;

import es.ubu.batchdownloader.common.BadRequestException;
import java.util.List;
import java.util.Set;

/**
 * Valida un destino Linux explícito y determina el orden de formatos compatibles para seleccionar
 * un instalador.
 *
 * @param manager Gestor Linux: apt, dnf, pacman, zypper o portable.
 * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
 * @see es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup
 * @see es.ubu.batchdownloader.downloads.application.DownloadSelection
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
public record LinuxTarget(String manager, String architecture) {
    /**
     * Rechaza gestores y arquitecturas fuera de los conjuntos soportados.
     *
     * @param manager Gestor Linux: apt, dnf, pacman, zypper o portable.
     * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el gestor o la arquitectura
     *     faltan o no están soportados.
     */
    public LinuxTarget {
        if (!Set.of("apt", "dnf", "pacman", "zypper", "portable").contains(manager == null ? "" : manager)
                || !Set.of("x86_64", "x86", "aarch64").contains(architecture == null ? "" : architecture)) {
            throw new BadRequestException("invalid_linux_target", "Indica distribución y arquitectura Linux.");
        }
    }

    /**
     * Construye el destino únicamente para una selección exclusiva de Linux; la ausencia de ambos
     * campos conserva selección automática.
     *
     * @param manager Gestor Linux: apt, dnf, pacman, zypper o portable.
     * @param architecture Arquitectura del destino: x86_64, x86 o aarch64.
     * @param systems Plataformas solicitadas; un destino explícito exige únicamente linux.
     * @return destino validado o null cuando no se indicó ninguno.
     * @throws es.ubu.batchdownloader.common.BadRequestException si se mezcla un destino con otras
     *     plataformas o sus campos son inválidos.
     */
    public static LinuxTarget optional(String manager, String architecture, List<String> systems) {
        if (manager == null && architecture == null) return null;
        if (!List.of("linux").equals(systems)) {
            throw new BadRequestException("linux_target_requires_linux", "El destino solo se aplica a lotes Linux.");
        }
        return new LinuxTarget(manager, architecture);
    }

    /**
     * Prioriza el paquete nativo del gestor y después AppImage, tar.gz y JAR; portable utiliza solo
     * formatos portables.
     *
     * @return extensiones compatibles en el orden de selección.
     */
    public List<String> extensions() {
        return switch (manager) {
            case "apt" -> List.of(".deb", ".appimage", ".tar.gz", ".jar");
            case "dnf", "zypper" -> List.of(".rpm", ".appimage", ".tar.gz", ".jar");
            case "pacman" -> List.of(".pkg.tar.zst", ".appimage", ".tar.gz", ".jar");
            default -> List.of(".appimage", ".tar.gz", ".jar");
        };
    }
}

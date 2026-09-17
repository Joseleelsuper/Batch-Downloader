package es.ubu.batchdownloader.catalog;

import java.util.List;
import java.util.Locale;

/**
 * Describe compatibilidad de formatos y grado de automatización Linux para las opciones del
 * catálogo y la admisión de descargas.
 *
 * @see CatalogDtos.DownloadOption
 * @see es.ubu.batchdownloader.downloads.application.LinuxTarget
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
public final class LinuxInstallationSupport {
    private static final String MANUAL = "manual";

    /**
     * Impide instancias de la clasificación estática de formatos Linux.
     */
    private LinuxInstallationSupport() {}

    /**
     * Asocia deb con apt, rpm con dnf o zypper y pkg.tar.zst con pacman; AppImage, tar.gz y jar
     * permiten también destino portable.
     *
     * @param extension Extensión del instalador con punto inicial, o null si no se conoce.
     * @return gestores compatibles o lista vacía para formatos desconocidos.
     */
    public static List<String> targets(String extension) {
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case ".deb" -> List.of("apt");
            case ".rpm" -> List.of("dnf", "zypper");
            case ".pkg.tar.zst" -> List.of("pacman");
            case ".appimage", ".tar.gz", ".jar" -> List.of("apt", "dnf", "pacman", "zypper", "portable");
            default -> List.of();
        };
    }

    /**
     * Utiliza el perfil aprobado cuando existe; sin perfil automatiza paquetes nativos y AppImage y
     * clasifica el resto como manual.
     *
     * @param os Plataforma a la que pertenece el instalador; solo linux necesita clasificación de
     *     instalación.
     * @param extension Extensión del instalador con punto inicial, o null si no se conoce.
     * @param approvedStrategy Estrategia de un perfil Linux aprobado; null utiliza la clasificación
     *     por extensión.
     * @return not_applicable fuera de Linux, o automatic o manual en Linux.
     */
    public static String support(String os, String extension, String approvedStrategy) {
        if (!"linux".equals(os)) return "not_applicable";
        if (approvedStrategy != null) return MANUAL.equals(approvedStrategy) ? MANUAL : "automatic";
        return switch (extension == null ? "" : extension.toLowerCase(Locale.ROOT)) {
            case ".deb", ".rpm", ".pkg.tar.zst", ".appimage" -> "automatic";
            default -> MANUAL;
        };
    }
}
